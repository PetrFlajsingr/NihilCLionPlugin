package cz.nihil_engine.nihil_utils_plugin.debugger

import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.ui.ConsoleView
import com.intellij.ide.ActivityTracker
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerManagerListener
import com.jetbrains.cidr.execution.debugger.CidrDebugProcess
import com.jetbrains.cidr.execution.debugger.CidrStackFrame
import cz.nihil_engine.nihil_utils_plugin.asserts.IgnoreListService
import cz.nihil_engine.nihil_utils_plugin.project.NihilFeature
import cz.nihil_engine.nihil_utils_plugin.project.NihilProjectConfigService
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** What the plugin knows about one debug session of a NihilEngine program. */
class AssertBreakSessionState {
    /** Runtime's per-user directory, from its startup log line; holds ignored_asserts.txt. */
    @Volatile var userDataDirectory: String? = null
    /** The assert break the session is stopped on, or null. */
    @Volatile var currentHit: AssertBreakHit? = null
    /** IDs ignored in this session, for sites that couldn't be patched: resumed automatically. */
    val ignoredIds: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    @Volatile var notification: Notification? = null
    /** Bumped on every pause and resume, so an analysis that finishes after its stop is over is dropped. */
    val stop = AtomicInteger()
}

/**
 * "Ignore assert" for stops on NihilEngine assert breaks. NihilEngine's own ignore list (IgnoreListAssertFailureHandler)
 * has no UI while a debugger is attached; this fills that gap from the IDE:
 *
 * - Ignore: disables this break site in the running process (see [AssertBreakAnalyzer.planPatch]) and continues.
 * - Ignore every run: also appends the ID to `<user data directory>/ignored_asserts.txt`, which the runtime reads at
 *   startup, so later runs don't break on it at all.
 *
 * Fatal asserts terminate right after the break, so they get no ignore option.
 */
@Service(Service.Level.PROJECT)
class AssertBreakService(private val project: Project) {

    private val log = Logger.getInstance(AssertBreakService::class.java)

    fun state(session: XDebugSession?): AssertBreakSessionState? = session?.debugProcess?.processHandler?.getUserData(STATE_KEY)

    /** Called by [AssertBreakDebuggerListener] for every CIDR debug process. */
    fun track(process: CidrDebugProcess) {
        val handler = process.processHandler
        if (NihilProjectConfigService.isEnabled(project, NihilFeature.IGNORED_ASSERTS)) {
            // Called when the debug process is created, before the program writes anything.
            IgnoreListService.getInstance(project).watchOutput(handler)
        }
        val state = AssertBreakSessionState()
        handler.putUserData(STATE_KEY, state)
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                if (state.userDataDirectory == null) {
                    IgnoreListService.USER_DATA_DIR.find(event.text)?.let { state.userDataDirectory = it.groupValues[1] }
                }
            }
        })
        val session = process.session
        session.addSessionListener(object : XDebugSessionListener {
            override fun sessionPaused() = onPaused(session, process, state)
            override fun sessionResumed() { clear(state) }
            override fun sessionStopped() { clear(state) }
        })
    }

    private fun onPaused(session: XDebugSession, process: CidrDebugProcess, state: AssertBreakSessionState) {
        val stop = clear(state)
        if (!NihilProjectConfigService.isEnabled(project, NihilFeature.ASSERT_BREAK_IGNORE)) return
        val frame = session.suspendContext?.activeExecutionStack?.topFrame as? CidrStackFrame ?: return
        AssertBreakAnalyzer.analyze(process, frame.thread).whenComplete { hit, error ->
            if (error != null) {
                log.info("Assert break analysis failed", error)
                return@whenComplete
            }
            if (hit == null || !session.isSuspended || state.stop.get() != stop) return@whenComplete
            if (hit.site.id in state.ignoredIds) {
                log.info("${hit.site.idText} is ignored in this session; resuming")
                session.resume()
                return@whenComplete
            }
            state.currentHit = hit
            ApplicationManager.getApplication().invokeLater {
                // The debugger toolbar updated when the session paused, before this analysis finished: refresh it so
                // the Ignore actions see the hit. Nothing else triggers an update when the stop follows an Ignore quickly.
                ActivityTracker.getInstance().inc()
                notify(session, state, hit)
            }
        }
    }

    /** Forgets the current stop; returns the new stop number. */
    private fun clear(state: AssertBreakSessionState): Int {
        val stop = state.stop.incrementAndGet()
        val hadHit = state.currentHit != null
        state.currentHit = null
        val notification = state.notification
        state.notification = null
        if (notification != null || hadHit) {
            ApplicationManager.getApplication().invokeLater {
                notification?.expire()
                ActivityTracker.getInstance().inc()
            }
        }
        return stop
    }

    private fun notify(session: XDebugSession, state: AssertBreakSessionState, hit: AssertBreakHit) {
        if (state.currentHit !== hit || project.isDisposed) return
        val site = hit.site
        val where = "${File(hit.file).name}:${site.line + 1}"
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(
                "${site.kind.typeName} ${site.idText} failed",
                if (site.kind.fatal) "$where. ${site.macro} is fatal: the program terminates after continuing."
                else where,
                if (site.kind.fatal) NotificationType.ERROR else NotificationType.WARNING,
            )
        if (!site.kind.fatal) {
            notification.addAction(NotificationAction.createSimple("Ignore") { ignore(session, persistent = false) })
            notification.addAction(NotificationAction.createSimple("Ignore every run") { ignore(session, persistent = true) })
        }
        state.notification = notification
        notification.notify(project)
    }

    /**
     * The program behind [console] logged its user data directory (seen by UserDataDirectoryInputFilter). This is how
     * sessions learn it when the program writes to CLion's terminal debug console, whose output never reaches the
     * process handler's text events.
     */
    fun userDataDirectoryLogged(console: ConsoleView, directory: String) {
        val tracked = XDebuggerManager.getInstance(project).debugSessions.mapNotNull { session ->
            val process = session.debugProcess as? CidrDebugProcess ?: return@mapNotNull null
            state(session)?.let { process to it }
        }
        // The console may be wrapped; then fall back to the one session still waiting for its directory, if unambiguous.
        val state = tracked.firstOrNull { (process, _) -> process.getConsole() === console }?.second
            ?: tracked.map { it.second }.singleOrNull { it.userDataDirectory == null }
            ?: return
        state.userDataDirectory = directory
    }

    /** Why "Ignore every run" can't be used right now, or null when it can. */
    fun persistentIgnoreProblem(session: XDebugSession?): String? {
        val state = state(session) ?: return "Not a tracked debug session"
        return if (state.userDataDirectory == null) {
            "The program didn't log its user data directory (\"User data directory: '...'\"), so there is no ignored_asserts.txt to add to"
        } else null
    }

    fun ignore(session: XDebugSession, persistent: Boolean) {
        val state = state(session) ?: return
        val hit = state.currentHit ?: return
        if (hit.site.kind.fatal) return
        val process = session.debugProcess as? CidrDebugProcess ?: return

        if (persistent) {
            val problem = persistentIgnoreProblem(session)
            if (problem != null) {
                warn("Can't ignore ${hit.site.idText} every run", problem)
            } else {
                IgnoreListService.getInstance(project).ignore(hit.site.id, state.userDataDirectory!!)?.let {
                    warn("Can't ignore ${hit.site.idText} every run", it)
                }
            }
        }

        state.ignoredIds += hit.site.id
        val patch = hit.patch
        if (patch == null) {
            session.resume()
            return
        }
        AssertBreakAnalyzer.applyPatch(process, patch).whenComplete { ok, _ ->
            log.info("Patch ${patch.description} at ${patch.address} for ${hit.site.idText}: ${if (ok == true) "applied" else "failed"}")
            if (ok != true) log.info("Could not patch ${patch.description} at ${patch.address}; ${hit.site.idText} is resumed automatically instead")
            session.resume()
        }
    }

    private fun warn(title: String, content: String) {
        ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification(title, content, NotificationType.WARNING).notify(project)
        }
    }

    companion object {
        const val NOTIFICATION_GROUP = "Nihil Asserts"
        private val STATE_KEY = Key.create<AssertBreakSessionState>("nihil.assertBreakState")

        fun getInstance(project: Project): AssertBreakService = project.getService(AssertBreakService::class.java)
    }
}

/** Starts tracking every CIDR (CLion native) debug process. */
class AssertBreakDebuggerListener(private val project: Project) : XDebuggerManagerListener {
    override fun processStarted(debugProcess: XDebugProcess) {
        if (debugProcess is CidrDebugProcess) AssertBreakService.getInstance(project).track(debugProcess)
    }
}
