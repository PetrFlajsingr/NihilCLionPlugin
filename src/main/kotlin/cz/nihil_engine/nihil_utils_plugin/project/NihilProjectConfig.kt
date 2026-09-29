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
    /** Commit check: build and run the tests of every library the commit touches. */
    COMMIT_TESTS("commit_tests"),
    /** Commit check: block assert IDs that the commit duplicates. */
    COMMIT_ASSERT_IDS("commit_assert_ids"),
    /** Inlays at NIHIL_IS_ENABLED(X) with X's value in every build type, and a flag matrix tool window. */
    FEATURE_FLAGS("feature_flags"),
    /** Console variable index and navigation, plus live values from a running app's console control server. */
    CVARS("cvars"),
    /** Excludes engine/ while the selected profile's NIHIL_ENGINE_DIR is a linked checkout, and disabled profiles' build dirs. */
    ENGINE_ROOT_EXCLUSION("engine_root_exclusion"),
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

data class CommitTestsConfig(
    /** CMake profile the tests are built and run with: its name, or a preset profile's display name. */
    val profile: String = "Test (release)",
    /** Library name -> test targets, replacing the `NihilTest<Name>` convention for that library. */
    val targets: Map<String, List<String>> = emptyMap(),
)

data class CVarsConfig(
    /** TCP port of the app's console control server (`AppConfig::consoleControlPort`). */
    val port: Int = 8344,
    /** How often live values are re-listed while connected and shown; the server doesn't push changes. */
    val pollIntervalMs: Int = 2000,
)

data class NihilProjectConfig(
    /** False when `.idea/nihil_plugin.toml` doesn't exist; every feature is then off. */
    val present: Boolean,
    val features: Set<NihilFeature>,
    val buildTargets: BuildTargetsConfig,
    val commitTests: CommitTestsConfig = CommitTestsConfig(),
    val cvars: CVarsConfig = CVarsConfig(),
    /** Problems found while reading the file, shown to the user where the feature is used. */
    val problems: List<String> = emptyList(),
) {
    fun isEnabled(feature: NihilFeature): Boolean = feature in features

    companion object {
        val ABSENT = NihilProjectConfig(present = false, features = emptySet(), buildTargets = BuildTargetsConfig())
    }
}
