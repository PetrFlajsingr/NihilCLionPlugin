package cz.nihil_engine.nihil_utils_plugin.args

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import java.io.File

@Service(Service.Level.PROJECT)
class NihilArgsConfigService(private val project: Project) : Disposable {

    private val log = Logger.getInstance(NihilArgsConfigService::class.java)

    var config: NihilArgsConfig = NihilArgsConfig(emptyList())
        private set

    private val listeners = mutableListOf<() -> Unit>()

    private val configFile: File
        get() = File(project.basePath ?: "", ".idea/nihil_args.toml")

    init {
        reload()

        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    val relevant = events.any { event ->
                        event.path.endsWith("nihil_args.toml")
                    }
                    if (relevant) {
                        reload()
                        notifyListeners()
                    }
                }
            }
        )
    }

    fun addChangeListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeChangeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        listeners.forEach { it() }
    }

    private fun reload() {
        config = try {
            NihilArgsConfigParser.parse(configFile)
        } catch (e: Exception) {
            log.warn("Failed to parse nihil_args.toml", e)
            NihilArgsConfig(emptyList())
        }
        log.info("Loaded nihil_args.toml: ${config.profiles.size} profiles")
    }

    // --- State persistence ---

    private fun stateKey(profileKey: String, argKey: String): String =
        "nihil.args.$profileKey.$argKey"

    fun getValue(profile: TargetProfile, arg: ArgDefinition): String {
        val props = PropertiesComponent.getInstance(project)
        return props.getValue(stateKey(profile.key, arg.key), arg.default)
    }

    fun setValue(profile: TargetProfile, arg: ArgDefinition, value: String) {
        val props = PropertiesComponent.getInstance(project)
        props.setValue(stateKey(profile.key, arg.key), value, arg.default)
    }

    fun getBoolValue(profile: TargetProfile, arg: ArgDefinition): Boolean =
        getValue(profile, arg).toBooleanStrictOrNull() ?: arg.default.toBooleanStrictOrNull() ?: false

    fun setBoolValue(profile: TargetProfile, arg: ArgDefinition, value: Boolean) =
        setValue(profile, arg, value.toString())

    /**
     * Build the command line arguments for a given target name.
     * Returns empty list if no profile matches.
     */
    fun buildCommandLineArgs(targetName: String): List<String> {
        val profile = config.findProfile(targetName) ?: return emptyList()
        return buildList {
            for (arg in profile.args) {
                when (arg.type) {
                    ArgType.BOOL -> {
                        if (getBoolValue(profile, arg)) {
                            add(arg.flag)
                        }
                    }
                    ArgType.SELECT -> {
                        val value = getValue(profile, arg)
                        if (value.isNotEmpty()) {
                            add(arg.flag)
                            add(value)
                        }
                    }
                    ArgType.TEXT -> {
                        val value = getValue(profile, arg)
                        if (value.isNotEmpty()) {
                            add(arg.flag)
                            add(value)
                        }
                    }
                }
            }
        }
    }

    override fun dispose() {
        listeners.clear()
    }

    companion object {
        fun getInstance(project: Project): NihilArgsConfigService =
            project.getService(NihilArgsConfigService::class.java)
    }
}
