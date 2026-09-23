package cz.nihil_engine.nihil_utils_plugin.project

import com.intellij.ide.ActivityTracker
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.messages.Topic
import java.io.File

/**
 * Per-project opt-in: parses the committed `.idea/nihil_plugin.toml` and reloads it on VFS change.
 * Without the file every Nihil feature is off.
 */
@Service(Service.Level.PROJECT)
class NihilProjectConfigService(private val project: Project) : Disposable {

    private val log = Logger.getInstance(NihilProjectConfigService::class.java)

    @Volatile
    var config: NihilProjectConfig = NihilProjectConfig.ABSENT
        private set

    private val configFile: File
        get() = File(project.basePath ?: "", ".idea/$FILE_NAME")

    init {
        reload()

        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    val path = FileUtil.toSystemIndependentName(configFile.path)
                    if (events.any { FileUtil.pathsEqual(it.path, path) }) {
                        reload()
                        // Toolbars and menus pick up the new feature set on their next update.
                        ActivityTracker.getInstance().inc()
                        project.messageBus.syncPublisher(TOPIC).configChanged()
                    }
                }
            }
        )
    }

    fun isEnabled(feature: NihilFeature): Boolean = config.isEnabled(feature)

    private fun reload() {
        config = try {
            NihilProjectConfigParser.parse(configFile)
        } catch (e: Exception) {
            log.warn("Failed to parse $FILE_NAME", e)
            NihilProjectConfig.ABSENT
        }
        if (config.present) {
            log.info("Loaded $FILE_NAME: features=${config.features.map { it.key }}")
            config.problems.forEach { log.warn("$FILE_NAME: $it") }
        }
    }

    override fun dispose() {}

    /** For UI that isn't an action (tool windows, gutter markers), which doesn't update on its own. */
    fun interface Listener {
        fun configChanged()
    }

    companion object {
        const val FILE_NAME = "nihil_plugin.toml"

        @Topic.ProjectLevel
        val TOPIC = Topic(Listener::class.java)

        fun getInstance(project: Project): NihilProjectConfigService =
            project.getService(NihilProjectConfigService::class.java)

        fun isEnabled(project: Project?, feature: NihilFeature): Boolean =
            project != null && !project.isDisposed && getInstance(project).isEnabled(feature)
    }
}
