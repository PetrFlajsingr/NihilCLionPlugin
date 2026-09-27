package cz.nihil_engine.nihil_utils_plugin.cvars

import org.junit.Assert.assertEquals
import org.junit.Test

class CVarConsoleFilterTest {

    private val names = setOf("r.vsync", "r.dump_rdg", "ResetCamera", "test.debug_mode")
    private val prefixes = setOf("r.demo")

    private fun links(line: String) = CVarConsoleFilter.findLinks(line, names, prefixes).map { (range, name) -> line.substring(range) to name }

    @Test
    fun `known names and members of known prefixes become links`() {
        assertEquals(
            listOf("r.vsync" to "r.vsync", "r.demo.tonemap.exposure" to "r.demo.tonemap.exposure"),
            links("[Info] r.vsync = true, r.demo.tonemap.exposure = 2"),
        )
        assertEquals(listOf("ResetCamera" to "ResetCamera"), links("> ResetCamera"))
    }

    @Test
    fun `other dotted words and partial matches stay plain`() {
        assertEquals(emptyList<Pair<String, String>>(), links("Loaded shaders/r.vsync.hlsl from config.toml; r.vsyncs; my.r.vsync; r.demo"))
    }
}
