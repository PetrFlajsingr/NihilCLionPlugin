package cz.nihil_engine.nihil_utils_plugin.args

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ArgPresetsTomlTest {

    private val toml = """
        [engine]
        label = "Engine"
        filter = "NihilGame"

        [engine.args.frames]
        type = "int"
        default = 8

        [engine.args.crash]
        type = "bool"
        default = false

        [engine.args.features]
        type = "multi"
        options = ["raytracing", "dlss", "hdr"]

        [engine.args.scene]
        type = "text"
        default = "sponza"

        [engine.presets.perf]
        label = "Perf test"
        frames = 100
        crash = false
        features = ["raytracing", "dlss"]
        scene = "${'$'}{PROJECT_DIR}/scenes/bistro"
    """.trimIndent()

    private fun parse(text: String): NihilArgsConfig {
        val file = File.createTempFile("nihil_args", ".toml").apply { deleteOnExit() }
        file.writeText(text)
        return NihilArgsConfigParser.parse(file)
    }

    @Test
    fun `presets are read in stored format`() {
        val preset = parse(toml).profiles.single().presets.single()
        assertEquals("perf", preset.key)
        assertEquals("Perf test", preset.label)
        assertEquals(
            mapOf("frames" to "100", "crash" to "false", "features" to "raytracing|dlss", "scene" to "\${PROJECT_DIR}/scenes/bistro"),
            preset.values,
        )
    }

    @Test
    fun `presets survive a write and read back`() {
        val config = parse(toml)
        val written = NihilArgsConfigWriter.toToml(config)
        assertEquals(config.profiles.single().presets, parse(written).profiles.single().presets)
    }
}
