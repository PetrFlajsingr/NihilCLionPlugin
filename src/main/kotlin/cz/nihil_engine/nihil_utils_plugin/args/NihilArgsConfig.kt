package cz.nihil_engine.nihil_utils_plugin.args

enum class ArgType { BOOL, SELECT, TEXT, PATH, INT, MULTI, DERIVED }

enum class PathKind { FILE, DIRECTORY }
enum class PathDirection { INPUT, OUTPUT }

data class ArgDefinition(
    val key: String,
    val label: String,
    val type: ArgType,
    val flag: String,
    val default: String,
    val options: List<String> = emptyList(),
    /** Template string for DERIVED args, e.g. "${PROJECT_DIR}/paths_${render_backend}.toml" */
    val valueTemplate: String = "",
    val pathKind: PathKind = PathKind.FILE,
    val pathDirection: PathDirection = PathDirection.INPUT,
    val min: Int? = null,
    val max: Int? = null,
    /** Separator used to join MULTI selections when building CLI args */
    val separator: String = ",",
)

data class ArgPreset(
    val key: String,
    val label: String,
    val values: Map<String, String>,
)

data class TargetProfile(
    val key: String,
    val label: String,
    val filter: Regex,
    val args: List<ArgDefinition>,
    /** Team presets from nihil_args.toml, personal ones live in [NihilArgsPresetStore]. */
    val presets: List<ArgPreset> = emptyList(),
)

data class NihilArgsConfig(
    val profiles: List<TargetProfile>,
) {
    fun findProfile(targetName: String): TargetProfile? =
        profiles.firstOrNull { it.filter.containsMatchIn(targetName) }
}