package cz.nihil_engine.nihil_utils_plugin.project

import cz.nihil_engine.nihil_utils_plugin.util.SimpleToml
import org.junit.Assert.assertEquals
import org.junit.Test

class NihilProjectConfigWriterTest {

    private fun parse(text: String) = NihilProjectConfigParser.fromTables(SimpleToml.parse(text.lines()))

    private fun config(vararg features: NihilFeature, build: BuildTargetsConfig = BuildTargetsConfig(), tests: CommitTestsConfig = CommitTestsConfig()) =
        NihilProjectConfig(present = true, features = features.toSet(), buildTargets = build, commitTests = tests)

    @Test
    fun `a new file holds only what differs from the defaults`() {
        val text = NihilProjectConfigWriter.update(null, config(NihilFeature.ASSERT_MENU, NihilFeature.COMMIT_TESTS))
        assertEquals(
            """
            ${NihilProjectConfigWriter.HEADER}

            [features]
            assert_menu = true
            commit_tests = true

            """.trimIndent(),
            text,
        )
    }

    @Test
    fun `edits keep comments, order and unknown keys`() {
        val existing = """
            # Only some features
            [features]
            build_target_selector = true
            tool_buttons = false         # no buttons here
            hot_reload = true   # deliberate: unknown feature

            # A non-default target setup.
            [build_targets]
            cmake_variable = "APP_TARGET"
            targets = ["Client", "Server"]
            default_target = "Client"
            profile_name = "{target}-{variant}"
        """.trimIndent()
        val build = BuildTargetsConfig("APP_TARGET", listOf("Client", "Server", "Bot"), "Client", "{target}-{variant}")
        val text = NihilProjectConfigWriter.update(existing, config(NihilFeature.TOOL_BUTTONS, NihilFeature.CONSOLE_LINKS, build = build))
        assertEquals(
            """
            # Only some features
            [features]
            build_target_selector = false
            tool_buttons = true         # no buttons here
            hot_reload = true   # deliberate: unknown feature
            console_links = true

            # A non-default target setup.
            [build_targets]
            cmake_variable = "APP_TARGET"
            targets = ["Client", "Server", "Bot"]
            default_target = "Client"
            profile_name = "{target}-{variant}"

            """.trimIndent(),
            text,
        )
    }

    @Test
    fun `new keys go before the blank line that ends their table`() {
        val existing = "[features]\nassert_menu = true\n\n[build_targets]\ncmake_variable = \"X\"\n"
        val text = NihilProjectConfigWriter.update(existing, config(NihilFeature.ASSERT_MENU, NihilFeature.NEW_MODULE, build = BuildTargetsConfig(cmakeVariable = "X")))
        assertEquals("[features]\nassert_menu = true\nnew_module = true\n\n[build_targets]\ncmake_variable = \"X\"\n", text)
    }

    @Test
    fun `target overrides are replaced as a set`() {
        val existing = "[commit_tests.targets]\nRDG = [\"NihilTestRDG_vulkan\"]\nLua = []\n"
        val tests = CommitTestsConfig("Test (debug)", mapOf("RDG" to listOf("NihilTestRDG_d3d12"), "RHI" to emptyList()))
        val text = NihilProjectConfigWriter.update(existing, config(tests = tests))
        assertEquals(
            "[commit_tests.targets]\nRDG = [\"NihilTestRDG_d3d12\"]\nRHI = []\n\n[commit_tests]\nprofile = \"Test (debug)\"\n",
            text,
        )
    }

    @Test
    fun `what it writes reads back the same`() {
        val original = config(
            NihilFeature.BUILD_TARGET_SELECTOR, NihilFeature.COMMIT_ASSERT_IDS,
            build = BuildTargetsConfig("V", listOf("A", "B \"quoted\""), "A", "{variant}/{target}"),
            tests = CommitTestsConfig("Test (release)", mapOf("Common" to listOf("NihilTestCommon", "NihilTestCommonSlow"))),
        )
        assertEquals(original, parse(NihilProjectConfigWriter.update(null, original)))
    }

    @Test
    fun `cvars table is written only when it differs from the defaults`() {
        val defaults = NihilProjectConfigWriter.update(null, config(NihilFeature.CVARS))
        assertEquals("${NihilProjectConfigWriter.HEADER}\n\n[features]\ncvars = true\n", defaults)

        val custom = config(NihilFeature.CVARS).copy(cvars = CVarsConfig(port = 9000))
        val text = NihilProjectConfigWriter.update(defaults, custom)
        assertEquals("${NihilProjectConfigWriter.HEADER}\n\n[features]\ncvars = true\n\n[cvars]\nport = 9000\n", text)
        assertEquals(CVarsConfig(port = 9000), parse(text).cvars)
    }
}
