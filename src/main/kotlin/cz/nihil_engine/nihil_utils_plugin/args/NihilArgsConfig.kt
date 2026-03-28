package cz.nihil_engine.nihil_utils_plugin.args

enum class ArgType { BOOL, SELECT, TEXT, DERIVED }

data class ArgDefinition(
    val key: String,
    val label: String,
    val type: ArgType,
    val flag: String,
    val default: String,
    val options: List<String> = emptyList(),
    /** Template string for DERIVED args, e.g. "${PROJECT_DIR}/paths_${render_backend}.toml" */
    val valueTemplate: String = "",
)

data class TargetProfile(
    val key: String,
    val label: String,
    val filter: Regex,
    val args: List<ArgDefinition>,
)

data class NihilArgsConfig(
    val profiles: List<TargetProfile>,
) {
    fun findProfile(targetName: String): TargetProfile? =
        profiles.firstOrNull { it.filter.containsMatchIn(targetName) }
}