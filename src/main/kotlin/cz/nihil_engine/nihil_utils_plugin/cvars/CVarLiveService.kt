package cz.nihil_engine.nihil_utils_plugin.cvars

import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.messages.Topic
import cz.nihil_engine.nihil_utils_plugin.project.NihilFeature
import cz.nihil_engine.nihil_utils_plugin.project.NihilProjectConfigService
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Talks to a running app's ConsoleControlServer (newline-delimited JSON over TCP, see ConsoleControlServer.cpp).
 *
 * The server never announces changes, so while connected the listing is re-requested every poll interval, and
 * only while something that shows values is visible ([addInterest]). The app answers from its main loop: a
 * paused or breakpointed app doesn't answer, which shows as [Status.NOT_RESPONDING] instead of piling up
 * requests. Socket work never runs on the EDT.
 */
@Service(Service.Level.PROJECT)
class CVarLiveService(private val project: Project) : Disposable {

    enum class Status(val label: String) {
        DISCONNECTED("Not connected"),
        CONNECTING("Connecting…"),
        CONNECTED("Connected"),
        NOT_RESPONDING("Not responding (paused at a breakpoint?)"),
    }

    data class State(
        val status: Status = Status.DISCONNECTED,
        val port: Int? = null,
        /** The run configuration whose process owns the connection. */
        val appName: String? = null,
        val objects: Map<String, LiveObject> = emptyMap(),
        val lastListAt: Long = 0,
        /** Why the last connection ended or failed, shown while disconnected. */
        val message: String? = null,
    ) {
        val isConnected: Boolean get() = status == Status.CONNECTED || status == Status.NOT_RESPONDING
    }

    data class SetResult(val obj: LiveObject?, val error: String?, val output: String)

    private class PendingSet(val name: String, val future: CompletableFuture<SetResult>) {
        val output = StringBuilder()
    }

    private inner class Connection(val socket: Socket, val owner: ProcessHandler?) {
        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))
        @Volatile var closed = false
        @Volatile var listSentAt: Long? = null
        @Volatile var lastListRequest = 0L
        val pendingSets = ConcurrentLinkedDeque<PendingSet>()
        var poller: ScheduledFuture<*>? = null

        fun send(line: String): Boolean = try {
            synchronized(writer) {
                writer.write(line)
                writer.write("\n")
                writer.flush()
            }
            true
        } catch (e: IOException) {
            log.info("Console control send failed: ${e.message}")
            false
        }
    }

    private val log = Logger.getInstance(CVarLiveService::class.java)
    private val lock = Any()
    private val executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("Nihil console control", 4)
    private val interest = CopyOnWriteArrayList<() -> Boolean>()
    private val attempt = AtomicInteger()

    @Volatile
    var state = State()
        private set

    private var connection: Connection? = null

    /** Console output the app sent back, newest last. */
    private val outputLines = ConcurrentLinkedDeque<String>()

    val output: List<String> get() = outputLines.toList()

    private fun enabled() = NihilProjectConfigService.isEnabled(project, NihilFeature.CVARS)

    private fun config() = NihilProjectConfigService.getInstance(project).config.cvars

    /** Polling continues only while one of these says values are on screen. */
    fun addInterest(disposable: Disposable, visible: () -> Boolean) {
        interest += visible
        Disposer.register(disposable) { interest -= visible }
    }

    private fun interested() = interest.any { runCatching(it).getOrDefault(false) }

    private fun update(transform: (State) -> State) {
        synchronized(lock) { state = transform(state) }
        if (!project.isDisposed) project.messageBus.syncPublisher(TOPIC).liveChanged()
    }

    /**
     * A run or debug session started: connect once its server listens. The app logs "Console control server
     * listening on port N" when it's up; until that shows (or when output isn't captured) connecting is retried
     * for a while when [retry] (native run configurations), since the server starts only after launch.
     */
    fun processStarted(handler: ProcessHandler, appName: String, retry: Boolean) {
        if (!enabled()) return
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                ConsoleControlProtocol.LISTENING.find(event.text)?.let { m ->
                    val port = m.groupValues[1].toIntOrNull() ?: return
                    if (connection?.owner !== handler) connect(port, handler, appName, retryForMs = 5_000)
                }
                ConsoleControlProtocol.LISTEN_FAILED.find(event.text)?.let { m ->
                    val current = state
                    val holder = if (current.isConnected && current.appName != null) current.appName else "another process"
                    val note = "$appName couldn't open port ${m.groupValues[1]}: $holder has it (only one app can listen at a time)"
                    log.info(note)
                    if (current.isConnected) update { it.copy(message = note) } else if (current.status == Status.CONNECTING) {
                        attempt.incrementAndGet()
                        update { it.copy(status = Status.DISCONNECTED, message = note) }
                    }
                }
            }

            override fun processTerminated(event: ProcessEvent) {
                if (connection?.owner === handler) disconnect("$appName exited")
                else if (state.status == Status.CONNECTING && state.appName == appName) {
                    attempt.incrementAndGet()
                    update { it.copy(status = Status.DISCONNECTED, message = "$appName exited before its console server answered") }
                }
            }
        })
        val current = connection
        if (current != null && !current.closed && current.owner?.isProcessTerminated == false) {
            log.info("$appName started while connected to ${state.appName}; keeping that connection")
            return
        }
        if (!retry) return
        connect(config().port, handler, appName, retryForMs = 30_000, initialDelayMs = 500)
    }

    /** Connects to [port], retrying for [retryForMs]. Replaces any current connection once it succeeds. */
    fun connect(port: Int, owner: ProcessHandler?, appName: String, retryForMs: Long = 3_000, initialDelayMs: Long = 0) {
        val id = attempt.incrementAndGet()
        if (connection == null) update { it.copy(status = Status.CONNECTING, port = port, appName = appName, message = null) }
        executor.execute {
            if (initialDelayMs > 0) Thread.sleep(initialDelayMs)
            val deadline = System.currentTimeMillis() + retryForMs
            var lastError: String? = null
            while (attempt.get() == id && !project.isDisposed && owner?.isProcessTerminated != true) {
                val socket = Socket()
                try {
                    socket.connect(InetSocketAddress("127.0.0.1", port), 500)
                    if (attempt.get() != id) { socket.close(); return@execute }
                    established(socket, port, owner, appName)
                    return@execute
                } catch (e: IOException) {
                    runCatching { socket.close() }
                    lastError = e.message
                }
                if (System.currentTimeMillis() >= deadline) break
                Thread.sleep(500)
            }
            if (attempt.get() == id && connection == null) {
                update { it.copy(status = Status.DISCONNECTED, message = "Couldn't connect to port $port${lastError?.let { e -> " ($e)" } ?: ""}") }
            }
        }
    }

    private fun established(socket: Socket, port: Int, owner: ProcessHandler?, appName: String) {
        socket.tcpNoDelay = true
        val conn = Connection(socket, owner)
        val previous: Connection?
        synchronized(lock) {
            previous = connection
            connection = conn
        }
        previous?.let { close(it) }
        // The server lists everything on connect without being asked; that listing is the first answer.
        conn.listSentAt = System.currentTimeMillis()
        conn.lastListRequest = conn.listSentAt!!
        log.info("Connected to $appName's console control server on port $port")
        update { State(Status.CONNECTED, port, appName, emptyMap(), 0, null) }
        executor.execute { readLoop(conn) }
        conn.poller = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({ tick(conn) }, 250, 250, TimeUnit.MILLISECONDS)
    }

    private fun readLoop(conn: Connection) {
        try {
            val reader = BufferedReader(InputStreamReader(conn.socket.getInputStream(), StandardCharsets.UTF_8))
            while (!conn.closed) {
                val line = reader.readLine() ?: break
                handle(conn, ConsoleControlProtocol.parse(line) ?: continue)
            }
        } catch (e: IOException) {
            if (!conn.closed) log.info("Console control connection lost: ${e.message}")
        }
        if (!conn.closed && connection === conn) {
            val exiting = conn.owner?.let { it.isProcessTerminating || it.isProcessTerminated } == true
            disconnect("${state.appName ?: "The app"} ${if (exiting) "exited" else "closed the connection"}")
        }
    }

    private fun handle(conn: Connection, message: ServerMessage) {
        if (connection !== conn) return
        when (message) {
            is ServerMessage.Listing -> {
                conn.listSentAt = null
                val objects = message.objects.associateBy { it.name }
                update { it.copy(status = Status.CONNECTED, objects = objects, lastListAt = System.currentTimeMillis()) }
            }
            is ServerMessage.Value -> {
                val pending = conn.pendingSets.firstOrNull { it.name == message.obj.name }
                if (pending != null) {
                    conn.pendingSets.remove(pending)
                    val text = pending.output.toString()
                    pending.future.complete(SetResult(message.obj, ValueEditing.error(text), text))
                }
                update { it.copy(status = Status.CONNECTED, objects = it.objects + (message.obj.name to message.obj)) }
            }
            is ServerMessage.Output -> {
                conn.pendingSets.peekFirst()?.output?.append(message.text)
                message.text.trimEnd().lines().forEach { outputLines.addLast(it) }
                while (outputLines.size > MAX_OUTPUT_LINES) outputLines.pollFirst()
                update { it.copy(status = Status.CONNECTED) }
            }
            is ServerMessage.Unknown -> log.info("Console control: unknown message type '${message.type}'")
        }
    }

    private fun tick(conn: Connection) {
        if (conn.closed || connection !== conn) return
        val now = System.currentTimeMillis()
        val sentAt = conn.listSentAt
        if (sentAt != null) {
            // Don't pile requests on an app that isn't draining them.
            if (now - sentAt > RESPONSE_TIMEOUT_MS && state.status == Status.CONNECTED) update { it.copy(status = Status.NOT_RESPONDING) }
            return
        }
        if (interested() && now - conn.lastListRequest >= config().pollIntervalMs) requestList(conn)
    }

    private fun requestList(conn: Connection) {
        conn.lastListRequest = System.currentTimeMillis()
        conn.listSentAt = conn.lastListRequest
        if (!conn.send(ConsoleControlProtocol.list())) disconnect("Lost the connection to ${state.appName ?: "the app"}")
    }

    /** Re-lists now instead of at the next poll. */
    fun refresh() {
        val conn = connection ?: return
        if (conn.listSentAt == null) executor.execute { requestList(conn) }
    }

    /** Sets [name] in the running app; completes with the new value or an error, never on the EDT. */
    fun set(name: String, value: String): CompletableFuture<SetResult> {
        val conn = connection ?: return CompletableFuture.completedFuture(SetResult(null, "Not connected to a running app", ""))
        val pending = PendingSet(name, CompletableFuture())
        conn.pendingSets.addLast(pending)
        executor.execute {
            if (!conn.send(ConsoleControlProtocol.set(name, value))) {
                conn.pendingSets.remove(pending)
                pending.future.complete(SetResult(null, "Couldn't send to the app", ""))
            }
        }
        return pending.future.orTimeout(SET_TIMEOUT_MS, TimeUnit.MILLISECONDS).exceptionally { e ->
            conn.pendingSets.remove(pending)
            val reason = if (e is TimeoutException || e.cause is TimeoutException) {
                "The app didn't answer within ${SET_TIMEOUT_MS / 1000} s. It applies changes from its main loop: is it paused at a breakpoint? The value is set once it resumes."
            } else e.message ?: e.toString()
            SetResult(null, reason, pending.output.toString())
        }
    }

    /** Runs a console line (a command, or `name value`); its output lands in [output]. */
    fun exec(line: String) {
        val conn = connection ?: return
        outputLines.addLast("> $line")
        executor.execute { conn.send(ConsoleControlProtocol.exec(line)) }
    }

    fun disconnect(reason: String? = null) {
        attempt.incrementAndGet()
        val conn = synchronized(lock) { connection.also { connection = null } }
        conn?.let { close(it) }
        if (conn != null) log.info("Console control disconnected: ${reason ?: "by request"}")
        update { State(message = reason) }
    }

    private fun close(conn: Connection) {
        conn.closed = true
        conn.poller?.cancel(false)
        runCatching { conn.socket.close() }
        while (true) {
            val pending = conn.pendingSets.pollFirst() ?: break
            pending.future.complete(SetResult(null, "Disconnected", pending.output.toString()))
        }
    }

    override fun dispose() {
        attempt.incrementAndGet()
        synchronized(lock) { connection.also { connection = null } }?.let { close(it) }
    }

    fun interface Listener {
        /** Called on a background thread. */
        fun liveChanged()
    }

    companion object {
        const val RESPONSE_TIMEOUT_MS = 3_000L
        const val SET_TIMEOUT_MS = 5_000L
        private const val MAX_OUTPUT_LINES = 500

        @Topic.ProjectLevel
        val TOPIC = Topic(Listener::class.java)

        fun getInstance(project: Project): CVarLiveService = project.getService(CVarLiveService::class.java)
    }
}
