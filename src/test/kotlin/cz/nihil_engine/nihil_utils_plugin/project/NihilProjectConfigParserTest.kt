package cz.nihil_engine.nihil_utils_plugin.project

import cz.nihil_engine.nihil_utils_plugin.util.SimpleToml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NihilProjectConfigParserTest {

    private fun parse(text: String) = NihilProjectConfigParser.fromTables(SimpleToml.parse(text.lines()))

    @Test
    fun `missing file turns everything off`() {
        val config = NihilProjectConfigParser.parse(File("does/not/exist/nihil_plugin.toml"))
        assertFalse(config.present)
        NihilFeature.entries.forEach { assertFalse(config.isEnabled(it)) }
    }

    @Test
    fun `full example from the design`() {
        val config = parse(
            """
            [features]
            build_target_selector = true
            tool_buttons = true          # Dashboard, Tracy, RenderDoc, Log Viewer
            args_popup = false
            assert_menu = true

            [build_targets]
            cmake_variable = "NIHIL_BUILD_TARGET"
            targets = ["Game", "Editor", "Tools"]
            default_target = "Game"      # target when the option is absent
            profile_name = "{variant} {target}"   # Game profiles omit the target part
            """.trimIndent()
        )
        assertTrue(config.present)
        assertEquals(setOf(NihilFeature.BUILD_TARGET_SELECTOR, NihilFeature.TOOL_BUTTONS, NihilFeature.ASSERT_MENU), config.features)
        assertEquals(BuildTargetsConfig(), config.buildTargets)
        assertEquals(emptyList<String>(), config.problems)
    }

    @Test
    fun `empty file is present with every feature off and default build targets`() {
        val config = parse("")
        assertTrue(config.present)
        assertEquals(emptySet<NihilFeature>(), config.features)
        assertEquals(BuildTargetsConfig(), config.buildTargets)
    }

    @Test
    fun `bad values are reported and replaced`() {
        val config = parse(
            """
            [features]
            tool_buttons = yes
            speculo = true
            [build_targets]
            targets = ["Game", "Tools"]
            default_target = "Editor"
            profile_name = "{variant}"
            """.trimIndent()
        )
        assertFalse(config.isEnabled(NihilFeature.TOOL_BUTTONS))
        assertEquals("Game", config.buildTargets.defaultTarget)
        assertEquals("{variant} {target}", config.buildTargets.profileName)
        assertEquals(4, config.problems.size)
    }

    @Test
    fun `commit tests profile and target overrides`() {
        val config = parse(
            """
            [features]
            commit_tests = true
            commit_assert_ids = true
            [commit_tests]
            profile = "Test (debug)"
            [commit_tests.targets]
            RDG = ["NihilTestRDG_vulkan"]
            Lua = []
            Bad = "NihilTestBad"
            """.trimIndent()
        )
        assertTrue(config.isEnabled(NihilFeature.COMMIT_TESTS))
        assertTrue(config.isEnabled(NihilFeature.COMMIT_ASSERT_IDS))
        assertEquals("Test (debug)", config.commitTests.profile)
        assertEquals(mapOf("RDG" to listOf("NihilTestRDG_vulkan"), "Lua" to emptyList()), config.commitTests.targets)
        assertEquals(1, config.problems.size)
    }

    @Test
    fun `commit tests default to the release test profile`() {
        assertEquals(CommitTestsConfig(), parse("").commitTests)
        assertEquals("Test (release)", CommitTestsConfig().profile)
    }

    @Test
    fun `feature flags and cvars with port and poll interval`() {
        val config = parse(
            """
            [features]
            feature_flags = true
            cvars = true
            [cvars]
            port = 9000
            poll_interval_ms = 5_000
            """.trimIndent()
        )
        assertTrue(config.isEnabled(NihilFeature.FEATURE_FLAGS))
        assertTrue(config.isEnabled(NihilFeature.CVARS))
        assertEquals(CVarsConfig(9000, 5000), config.cvars)
        assertEquals(emptyList<String>(), config.problems)
    }

    @Test
    fun `bad cvar numbers fall back to the defaults`() {
        val config = parse("[cvars]\nport = 70000\npoll_interval_ms = often\n")
        assertEquals(CVarsConfig(), config.cvars)
        assertEquals(2, config.problems.size)
        assertEquals(CVarsConfig(), parse("").cvars)
    }
}
