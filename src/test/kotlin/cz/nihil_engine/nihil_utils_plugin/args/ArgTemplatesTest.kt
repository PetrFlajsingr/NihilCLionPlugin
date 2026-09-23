package cz.nihil_engine.nihil_utils_plugin.args

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArgTemplatesTest {

    private val builtins = mapOf("PROJECT_DIR" to "C:/proj")

    private fun resolve(vararg raw: Pair<String, String>, env: Map<String, String> = emptyMap()) =
        ArgTemplates.resolveAll(raw.toMap(), builtins) { env[it] }

    @Test
    fun `plain values pass through`() {
        assertEquals(ArgTemplates.Resolved("sponza"), resolve("scene" to "sponza")["scene"])
    }

    @Test
    fun `builtins and other args`() {
        val r = resolve("backend" to "vulkan", "paths" to "\${PROJECT_DIR}/config/paths_\${backend}.toml")
        assertEquals("C:/proj/config/paths_vulkan.toml", r.getValue("paths").value)
        assertTrue(r.getValue("paths").problems.isEmpty())
    }

    @Test
    fun `references chain in any order`() {
        val r = resolve("log" to "\${out}/log.txt", "out" to "\${root}/out", "root" to "\${PROJECT_DIR}")
        assertEquals("C:/proj/out/log.txt", r.getValue("log").value)
    }

    @Test
    fun `environment variables`() {
        val r = resolve("dir" to "\${env:LOCALAPPDATA}/Nihil", "missing" to "[\${env:NOPE}]", env = mapOf("LOCALAPPDATA" to "C:/AppData"))
        assertEquals("C:/AppData/Nihil", r.getValue("dir").value)
        assertEquals("[]", r.getValue("missing").value)
        assertEquals(listOf("environment variable NOPE is not set"), r.getValue("missing").problems)
    }

    @Test
    fun `unknown reference stays as written`() {
        val r = resolve("a" to "x\${nope}y")
        assertEquals("x\${nope}y", r.getValue("a").value)
        assertEquals(listOf("unknown reference \${nope}"), r.getValue("a").problems)
    }

    @Test
    fun `cycle is reported, not followed`() {
        val r = resolve("a" to "\${b}", "b" to "<\${a}>")
        assertEquals("<\${a}>", r.getValue("a").value)
        assertEquals(listOf("reference cycle: a -> b -> a"), r.getValue("b").problems)
    }
}
