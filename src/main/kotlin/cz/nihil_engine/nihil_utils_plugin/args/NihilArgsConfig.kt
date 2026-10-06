package cz.nihil_engine.nihil_utils_plugin.args

enum class ArgType {
    BOOL, SELECT, TEXT, PATH, INT, MULTI, DERIVED;

    /** BOOL is already presence-only and DERIVED has no user input, so neither can be toggled off. */
    val canBeOptional: Boolean get() = this != BOOL && this != DERIVED
}

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
    /** Optional args are only passed when enabled in the args panel; their value is kept while disabled. */
    val optional: Boolean = false,
    val enabledByDefault: Boolean = false,
    /** Advanced args are shown in a collapsed section of the args panel. */
    val advanced: Boolean = false,
) {
    val isOptional: Boolean get() = optional && type.canBeOptional
}

/**
 * Suffix of the value-map key that holds an optional arg's enabled state, e.g. "scene.enabled".
 * Arg keys come from TOML table names and never contain dots, so this cannot clash with a value key.
 */
const val ENABLED_SUFFIX = ".enabled"

fun enabledValueKey(argKey: String): String = argKey + ENABLED_SUFFIX

data class ArgPreset(
    val key: String,
    val label: String,
    /** Arg key -> value, plus [enabledValueKey] -> "true"/"false" for optional args. */
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