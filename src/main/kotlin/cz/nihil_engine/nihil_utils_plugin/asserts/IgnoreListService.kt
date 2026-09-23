package cz.nihil_engine.nihil_utils_plugin.asserts

import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.messages.Topic
import com.intellij.util.xmlb.annotations.XCollection
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class IgnoreList(val directory: String, val ids: Set<Long>) {
    val file: File get() = File(directory, IgnoreListService.FILE_NAME)
    /** The directory's name, e.g. "Game" for `.../NihilEngine/Game`. */
    val name: String get() = File(directory).name
}

@Service(Service.Level.PROJECT)
@State(name = "NihilIgnoreLists", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class IgnoreListService(private val project: Project) : PersistentStateComponent<IgnoreListService.ListsState>, Disposable {

    class ListsState {
        @XCollection(style = XCollection.Style.v2)
        var directories: MutableList<String> = mutableListOf()
    }

    fun interface Listener {
        fun listsChanged()
    }

    private val log = Logger.getInstance(IgnoreListService::class.java)
    private var state = ListsState()
    private val watches = ConcurrentHashMap<String, LocalFileSystem.WatchRequest>()
    private val reloads = AppExecutorUtil.createBoundedApplicationPoolExecutor("Nihil ignore lists", 1)

    @Volatile
    var lists: List<IgnoreList> = emptyList()
        private set

    init {
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                val files = directories().map { FileUtil.toSystemIndependentName(File(it, FILE_NAME).path) }
                if (events.any { e -> files.any { FileUtil.pathsEqual(it, e.path) } }) reloadAsync(refreshVfs = false)
            }
        })
    }

    override fun getState(): ListsState = synchronized(this) { state }

    override fun loadState(loaded: ListsState) {
        synchronized(this) { state = loaded }
        loaded.directories.forEach(::watch)
        reloadAsync(refreshVfs = true)
    }

    fun directories(): List<String> = synchronized(this) { state.directories.toList() }

    fun addDirectory(directory: String) {
        val dir = FileUtil.toSystemIndependentName(directory).trimEnd('/')
        val added = synchronized(this) {
            if (state.directories.any { FileUtil.pathsEqual(it, dir) }) false else state.directories.add(dir)
        }
        if (added) {
            log.info("Remembering ignore list directory $dir")
            watch(dir)
        }
        reloads.execute { createListFile(dir) }
        reloadAsync(refreshVfs = added)
    }

    private fun createListFile(directory: String) {
        val file = File(directory, FILE_NAME)
        if (!File(directory).isDirectory || file.exists()) return
        try {
            if (file.createNewFile()) log.info("Created empty $file")
        } catch (e: Exception) {
            log.warn("Failed to create $file", e)
        }
    }

    fun watchOutput(handler: ProcessHandler) {
        if (handler.getUserData(WATCHED_KEY) == true) return
        handler.putUserData(WATCHED_KEY, true)
        handler.addProcessListener(object : ProcessListener {
            @Volatile private var found = false

            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                if (found) return
                USER_DATA_DIR.find(event.text)?.let {
                    found = true
                    addDirectory(it.groupValues[1])
                }
            }

            override fun processTerminated(event: ProcessEvent) = reloadAsync(refreshVfs = true)
        })
    }

    fun removeDirectory(directory: String) {
        synchronized(this) { state.directories.removeIf { FileUtil.pathsEqual(it, directory) } }
        watches.remove(directory)?.let { LocalFileSystem.getInstance().removeWatchedRoot(it) }
        reloadAsync(refreshVfs = false)
    }

    fun listsIgnoring(id: Long): List<IgnoreList> = lists.filter { id in it.ids }

    fun ignore(id: Long, directory: String): String? {
        addDirectory(directory)
        return edit(File(directory, FILE_NAME)) { IgnoreListFormat.withAdded(it, id) }
    }

    fun unignore(id: Long, list: IgnoreList? = null): List<String> =
        (if (list != null) listOf(list) else listsIgnoring(id)).mapNotNull { l ->
            edit(l.file) { IgnoreListFormat.withRemoved(it, id) }
        }

    private fun edit(file: File, change: (String) -> String?): String? = try {
        val existing = if (file.exists()) file.readText() else ""
        change(existing)?.let { updated ->
            file.parentFile?.mkdirs()
            file.writeText(updated)
        }
        reloadAsync(refreshVfs = true)
        null
    } catch (e: Exception) {
        log.warn("Failed to update $file", e)
        "Failed to write $file: ${e.message}"
    }

    fun reloadAsync(refreshVfs: Boolean) {
        reloads.execute {
            val dirs = directories()
            val read = dirs.map { dir ->
                val file = File(dir, FILE_NAME)
                val ids = try {
                    if (file.exists()) IgnoreListFormat.parse(file.readText()) else emptySet()
                } catch (e: Exception) {
                    log.warn("Failed to read $file", e)
                    emptySet()
                }
                IgnoreList(dir, ids)
            }
            if (refreshVfs) {
                val lfs = LocalFileSystem.getInstance()
                val vDirs = dirs.mapNotNull { lfs.refreshAndFindFileByIoFile(File(it)) }
                vDirs.forEach { it.children }
                lfs.refreshFiles(vDirs, true, false, null)
            }
            if (read != lists) {
                lists = read
                ApplicationManager.getApplication().invokeLater({
                    project.messageBus.syncPublisher(TOPIC).listsChanged()
                }, project.disposed)
            }
        }
    }

    private fun watch(directory: String) {
        if (watches.containsKey(directory)) return
        LocalFileSystem.getInstance().addRootToWatch(directory, false)?.let { watches[directory] = it }
    }

    override fun dispose() {
        LocalFileSystem.getInstance().removeWatchedRoots(watches.values)
        watches.clear()
    }

    companion object {
        const val FILE_NAME = "ignored_asserts.txt"

        val USER_DATA_DIR = Regex("""User data directory: '([^'\r\n]+)'""")

        @Topic.ProjectLevel
        val TOPIC = Topic(Listener::class.java)

        private val WATCHED_KEY = Key.create<Boolean>("nihil.ignoreListWatched")

        fun getInstance(project: Project): IgnoreListService = project.getService(IgnoreListService::class.java)
    }
}
