package cz.nihil_engine.nihil_utils_plugin.build_targets

import cz.nihil_engine.nihil_utils_plugin.project.BuildTargetsConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildTargetGridTest {

    private val config = BuildTargetsConfig()

    /** Whitespace split that keeps double-quoted runs together, like CMakeSettings.getOptionsList. */
    private fun split(options: String): List<String> =
        Regex("""(?:[^\s"]+|"[^"]*")+""").findAll(options).map { it.value }.toList()

    private fun grid(vararg profiles: ProfileInfo) = BuildTargetGrid.build(profiles.toList(), config, ::split)

    private fun p(name: String, options: String = "", enabled: Boolean = true) = ProfileInfo(name, enabled, options)

    @Test
    fun `design table resolves without renaming`() {
        val g = grid(
            p("Development", "-DCMAKE_CXX_FLAGS=\"/GR- /utf-8\" -DNIHIL_ENABLE_TESTS=OFF"),
            p("Development Editor", "-DNIHIL_BUILD_TARGET=Editor"),
            p("Development Tools", "-DNIHIL_BUILD_TARGET=Tools"),
            p("Test (debug)", "-DNIHIL_ENABLE_TESTS=ON"),
            p("Development 26"),
            p("Development 26 Editor", "-DNIHIL_BUILD_TARGET=Editor"),
        )
        assertEquals(emptyList<GridWarning>(), g.warnings)
        assertEquals(listOf("Development", "Test (debug)", "Development 26"), g.variants)
        assertEquals(GridEntry("Development", "Development", "Game", true), g.entryForProfile("Development"))
        assertEquals("Editor", g.entryForProfile("Development Editor")!!.target)
        assertEquals("Development", g.entryForProfile("Development Tools")!!.variant)
        assertEquals(GridEntry("Test (debug)", "Test (debug)", "Game", true), g.entryForProfile("Test (debug)"))
        assertEquals(GridEntry("Development 26 Editor", "Development 26", "Editor", true), g.entryForProfile("Development 26 Editor"))
        assertEquals(listOf("Game", "Editor"), g.enabledTargets("Development 26"))
    }

    @Test
    fun `option forms and case are accepted`() {
        val g = grid(
            p("Debug Tools", "-DNIHIL_BUILD_TARGET:STRING=tools"),
            p("Release Tools", "-DNIHIL_BUILD_TARGET=\"Tools\""),
        )
        assertEquals(emptyList<GridWarning>(), g.warnings)
        assertEquals("Tools", g.entryForProfile("Debug Tools")!!.target)
        assertEquals("Release", g.entryForProfile("Release Tools")!!.variant)
    }

    @Test
    fun `name and option disagreeing is kept under the full name with a warning`() {
        val g = grid(
            p("Development"),
            p("Development Editor"), // no option: builds Game
        )
        val entry = g.entryForProfile("Development Editor")!!
        assertEquals("Development Editor", entry.variant)
        assertEquals("Game", entry.target)
        assertEquals(1, g.warningsFor("Development Editor").size)
        // the real Development Game profile is untouched
        assertEquals("Development", g.entry("Development", "Game")!!.profileName)
    }

    @Test
    fun `option target on a name without target part warns`() {
        val g = grid(p("Profiling", "-DNIHIL_BUILD_TARGET=Editor"))
        assertEquals(GridEntry("Profiling", "Profiling", "Editor", true), g.entryForProfile("Profiling"))
        assertEquals(1, g.warnings.size)
    }

    @Test
    fun `unknown target value and clashing pairs are reported, not placed`() {
        val g = grid(
            p("Development Server", "-DNIHIL_BUILD_TARGET=Server"),
            p("Development Game"), // Game with an explicit target part
            p("Development"),      // same pair (Development, Game)
        )
        assertNull(g.entryForProfile("Development Server"))
        assertNull(g.entryForProfile("Development"))
        assertEquals("Development Game", g.entry("Development", "Game")!!.profileName)
        assertEquals(2, g.warnings.size)
    }

    @Test
    fun `disabled profiles and variant switch fallback`() {
        val g = grid(
            p("Development"),
            p("Development Editor", "-DNIHIL_BUILD_TARGET=Editor"),
            p("Release", enabled = false),
            p("Release Tools", "-DNIHIL_BUILD_TARGET=Tools"),
            p("Profiling", enabled = false),
        )
        assertTrue(g.hasEnabledProfile("Release"))
        assertEquals(false, g.hasEnabledProfile("Profiling"))
        assertEquals("Editor", g.targetAfterVariantSwitch("Development", "Editor"))
        assertEquals("Game", g.targetAfterVariantSwitch("Development", "Tools"))
        // Release Game is disabled: fall back to the first enabled target
        assertEquals("Tools", g.targetAfterVariantSwitch("Release", "Editor"))
        assertNull(g.targetAfterVariantSwitch("Profiling", "Game"))
        assertEquals("Release Editor", g.expectedProfileName("Release", "Editor"))
        assertEquals("Release", g.expectedProfileName("Release", "Game"))
    }

    private fun preset(name: String, displayName: String, target: String? = null) = ProfileInfo(
        name, true, "--preset $name", displayName,
        presetCacheVariables = listOfNotNull(target?.let { "NIHIL_BUILD_TARGET" to it }).toMap(),
    )

    @Test
    fun `preset profiles use the display name and the preset's cache variables`() {
        val g = grid(
            preset("debug", "Debug", "Game"),
            preset("debug-editor", "Debug Editor", "Editor"),
            preset("debug-tools", "Debug Tools", "Tools"),
            preset("test-debug", "Test (debug)"),
        )
        assertEquals(emptyList<GridWarning>(), g.warnings)
        assertEquals(listOf("Debug", "Test (debug)"), g.variants)
        assertEquals(GridEntry("debug-editor", "Debug", "Editor", true, "Debug Editor"), g.entryForProfile("debug-editor"))
        assertEquals("Tools", g.entryForProfile("debug-tools")!!.target)
        assertEquals("Test (debug)", g.displayName("test-debug"))
    }

    @Test
    fun `preset whose display name disagrees with its cache variable warns under the display name`() {
        val g = grid(preset("profiling-editor", "Profiling Editor", "Game"))
        assertEquals("Profiling Editor", g.entryForProfile("profiling-editor")!!.variant)
        assertEquals("Profiling Editor", g.warnings.single().displayName)
    }

    @Test
    fun `custom name pattern`() {
        val g = BuildTargetGrid.build(
            listOf(p("Editor-Development", "-DNIHIL_BUILD_TARGET=Editor"), p("Development")),
            BuildTargetsConfig(profileName = "{target}-{variant}"),
            ::split,
        )
        assertEquals(emptyList<GridWarning>(), g.warnings)
        assertEquals("Development", g.entryForProfile("Editor-Development")!!.variant)
    }
}
