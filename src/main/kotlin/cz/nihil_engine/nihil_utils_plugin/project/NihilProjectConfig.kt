package cz.nihil_engine.nihil_utils_plugin.project

/** A plugin feature a project opts into through `.idea/nihil_plugin.toml`. */
enum class NihilFeature(val key: String) {
    BUILD_TARGET_SELECTOR("build_target_selector"),
    TOOL_BUTTONS("tool_buttons"),
    ARGS_POPUP("args_popup"),
    ASSERT_MENU("assert_menu"),
    /** Links for assert IDs, files and stack frames in run/debug console output. */
    CONSOLE_LINKS("console_links"),
    /** "Ignore assert" when the debugger stops on a NihilEngine assert break. */
    ASSERT_BREAK_IGNORE("assert_break_ignore"),
    /** Gutter markers and a tool window for asserts listed in the program's ignored_asserts.txt files. */
    IGNORED_ASSERTS("ignored_asserts"),
    /** File | New > Nihil Library, App or Tool, from the templates in .idea/nihil_templates. */
    NEW_MODULE("new_module"),
}

data class BuildTargetsConfig(
    /** CMake cache variable that selects the target, read from a profile's generation options. */
    val cmakeVariable: String = "NIHIL_BUILD_TARGET",
    val targets: List<String> = listOf("Game", "Editor", "Tools"),
    /** Target of a profile that doesn't set [cmakeVariable]; its profile name has no target part. */
    val defaultTarget: String = "Game",
    /** Profile name pattern with `{variant}` and `{target}` placeholders. */
    val profileName: String = "{variant} {target}",
)

data class NihilProjectConfig(
    /** False when `.idea/nihil_plugin.toml` doesn't exist; every feature is then off. */
    val present: Boolean,
    val features: Set<NihilFeature>,
    val buildTargets: BuildTargetsConfig,
    /** Problems found while reading the file, shown to the user where the feature is used. */
    val problems: List<String> = emptyList(),
) {
    fun isEnabled(feature: NihilFeature): Boolean = feature in features

    companion object {
        val ABSENT = NihilProjectConfig(present = false, features = emptySet(), buildTargets = BuildTargetsConfig())
    }
}
