package cz.nihil_engine.nihil_utils_plugin.args

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
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
    @Volatile private var suppressNextVfsReload = false

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
                        if (suppressNextVfsReload) {
                            suppressNextVfsReload = false
                        } else {
                            reload()
                            notifyListeners()
                        }
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

    fun save(newConfig: NihilArgsConfig) {
        config = newConfig
        try {
            val file = configFile
            NihilArgsConfigWriter.write(newConfig, file)
            suppressNextVfsReload = true
            ApplicationManager.getApplication().invokeLater {
                LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
            }
        } catch (e: Exception) {
            log.warn("Failed to write nihil_args.toml", e)
        }
        notifyListeners()
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

    fun getMultiValue(profile: TargetProfile, arg: ArgDefinition): List<String> =
        getValue(profile, arg).split("|").filter { it.isNotEmpty() }

    fun setMultiValue(profile: TargetProfile, arg: ArgDefinition, values: List<String>) =
        setValue(profile, arg, values.joinToString("|"))

    /** Non-optional args are always enabled. */
    fun isEnabled(profile: TargetProfile, arg: ArgDefinition): Boolean {
        if (!arg.isOptional) return true
        val props = PropertiesComponent.getInstance(project)
        return props.getBoolean(stateKey(profile.key, enabledValueKey(arg.key)), arg.enabledByDefault)
    }

    fun setEnabled(profile: TargetProfile, arg: ArgDefinition, enabled: Boolean) {
        val props = PropertiesComponent.getInstance(project)
        props.setValue(stateKey(profile.key, enabledValueKey(arg.key)), enabled, arg.enabledByDefault)
    }

    fun buildCommandLineArgs(targetName: String): List<String> {
        val profile = config.findProfile(targetName) ?: return emptyList()
        val resolved = resolvedValues(profile, targetName)

        return buildList {
            for (arg in profile.args) {
                if (!isEnabled(profile, arg)) continue
                when (arg.type) {
                    ArgType.BOOL -> if (getBoolValue(profile, arg)) add(arg.flag)
                    ArgType.MULTI -> {
                        val values = getMultiValue(profile, arg)
                        if (values.isNotEmpty()) {
                            add(arg.flag)
                            add(values.joinToString(arg.separator))
                        }
                    }
                    else -> {
                        val value = resolved[arg.key]?.value.orEmpty()
                        if (value.isNotEmpty()) {
                            add(arg.flag)
                            add(value)
                        }
                    }
                }
            }
        }
    }

    private fun rawValues(profile: TargetProfile): Map<String, String> =
        // A disabled optional arg is not passed, so templates referencing it see it as empty.
        profile.args.associate { arg -> arg.key to if (isEnabled(profile, arg)) rawValue(profile, arg) else "" }

    private fun rawValue(profile: TargetProfile, arg: ArgDefinition): String = when (arg.type) {
        ArgType.BOOL -> getBoolValue(profile, arg).toString()
        ArgType.MULTI -> getMultiValue(profile, arg).joinToString(arg.separator)
        ArgType.DERIVED -> arg.valueTemplate
        else -> getValue(profile, arg)
    }

    fun resolvedValues(profile: TargetProfile, targetName: String?): Map<String, ArgTemplates.Resolved> =
        ArgTemplates.resolveAll(rawValues(profile), builtins(targetName))

    fun builtins(targetName: String?): Map<String, String> = buildMap {
        put("PROJECT_DIR", project.basePath.orEmpty())
        put("PROJECT_NAME", project.name)
        if (targetName != null) put("RUN_CONFIG", targetName)
    }

    fun presets(profile: TargetProfile): List<PresetChoice> =
        profile.presets.map { PresetChoice(it, personal = false) } +
            NihilArgsPresetStore.getInstance(project).presets(profile.key).map { PresetChoice(it, personal = true) }

    fun currentValues(profile: TargetProfile): Map<String, String> = buildMap {
        for (arg in profile.args) {
            if (arg.type == ArgType.DERIVED) continue
            put(arg.key, getValue(profile, arg))
            if (arg.isOptional) put(enabledValueKey(arg.key), isEnabled(profile, arg).toString())
        }
    }

    fun applyValues(profile: TargetProfile, values: Map<String, String>) {
        for (arg in profile.args) {
            if (arg.type == ArgType.DERIVED) continue
            setValue(profile, arg, values[arg.key] ?: arg.default)
            if (arg.isOptional) setEnabled(profile, arg, presetEnabled(arg, values))
        }
    }

    fun matches(profile: TargetProfile, preset: ArgPreset): Boolean =
        profile.args.filter { it.type != ArgType.DERIVED }.all { arg ->
            val enabled = isEnabled(profile, arg)
            if (enabled != presetEnabled(arg, preset.values)) return@all false
            // The value of a disabled arg is never passed, so it doesn't distinguish presets.
            !enabled || normalized(arg, getValue(profile, arg)) == normalized(arg, preset.values[arg.key] ?: arg.default)
        }

    private fun presetEnabled(arg: ArgDefinition, values: Map<String, String>): Boolean =
        !arg.isOptional || (values[enabledValueKey(arg.key)]?.toBooleanStrictOrNull() ?: arg.enabledByDefault)

    private fun normalized(arg: ArgDefinition, value: String): String = when (arg.type) {
        ArgType.BOOL -> (value.toBooleanStrictOrNull() ?: false).toString()
        ArgType.INT -> value.trim().toIntOrNull()?.toString() ?: value
        ArgType.MULTI -> value.split("|").filter { it.isNotEmpty() }.sorted().joinToString("|")
        else -> value
    }

    override fun dispose() {
        listeners.clear()
    }

    companion object {
        fun getInstance(project: Project): NihilArgsConfigService =
            project.getService(NihilArgsConfigService::class.java)
    }
}