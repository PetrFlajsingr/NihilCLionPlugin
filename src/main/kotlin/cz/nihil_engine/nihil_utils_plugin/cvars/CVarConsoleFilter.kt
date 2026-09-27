package cz.nihil_engine.nihil_utils_plugin.cvars

import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import cz.nihil_engine.nihil_utils_plugin.asserts.AssertLocator

/**
 * Links cvar and command names in run/debug output to their declarations. Only names the index knows (or that
 * extend an indexed `$cvar{"prefix"}`) become links, so ordinary dotted words stay plain text.
 */
class CVarConsoleFilter(private val project: Project) : Filter {

    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val names = CVarNameCache.getInstance(project).snapshot()
        if (names.names.isEmpty()) return null
        val lineStart = entireLength - line.length
        val items = findLinks(line, names.names, names.prefixes).map { (range, name) ->
            Filter.ResultItem(lineStart + range.first, lineStart + range.last + 1, HyperlinkInfo { GoToCVarAction.navigate(it, name) })
        }
        return if (items.isEmpty()) null else Filter.Result(items)
    }

    companion object {
        private val TOKEN = Regex("""(?<![\w.])[A-Za-z_]\w*(?:\.\w+)*""")

        /** Ranges in [line] naming a known cvar or a member of a known prefix, with the name to look up. */
        fun findLinks(line: String, names: Set<String>, prefixes: Set<String>): List<Pair<IntRange, String>> =
            TOKEN.findAll(line).mapNotNull { m ->
                val token = m.value
                when {
                    token in names -> m.range to token
                    '.' in token && prefixes.any { token.startsWith("$it.") } -> m.range to token
                    else -> null
                }
            }.toList()
    }
}

/** The indexed names, refreshed in the background so console filters never wait for the index. */
@Service(Service.Level.PROJECT)
class CVarNameCache(private val project: Project) : Disposable {

    class Names(val names: Set<String>, val prefixes: Set<String>)

    @Volatile private var names = Names(emptySet(), emptySet())
    @Volatile private var loaded = false
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    init {
        project.messageBus.connect(this).apply {
            subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (events.any { it.path.substringAfterLast('.').lowercase() in AssertLocator.SOURCE_EXTENSIONS }) schedule(2_000)
                }
            })
            subscribe(DumbService.DUMB_MODE, object : DumbService.DumbModeListener {
                override fun exitDumbMode() = schedule(0)
            })
        }
    }

    fun snapshot(): Names {
        if (!loaded) {
            loaded = true
            schedule(0)
        }
        return names
    }

    private fun schedule(delayMs: Int) {
        alarm.cancelAllRequests()
        alarm.addRequest({
            ReadAction.nonBlocking<Names> {
                val all = CVarLocator.allNames(project)
                Names(all.filterValues { it.kind != CVarKind.PREFIX }.keys, all.filterValues { it.kind == CVarKind.PREFIX }.keys)
            }
                .inSmartMode(project)
                .expireWith(this)
                .submit(AppExecutorUtil.getAppExecutorService())
                .onSuccess { names = it }
        }, delayMs)
    }

    override fun dispose() {}

    companion object {
        fun getInstance(project: Project): CVarNameCache = project.getService(CVarNameCache::class.java)
    }
}
