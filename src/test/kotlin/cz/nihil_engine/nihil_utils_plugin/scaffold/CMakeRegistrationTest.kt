package cz.nihil_engine.nihil_utils_plugin.scaffold

import cz.nihil_engine.nihil_utils_plugin.util.SimpleToml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CMakeRegistrationTest {

    // The shape of NihilEngine's root CMakeLists.txt.
    private val root = """
        add_subdirectory(third_party)

        # Nihil libraries
        ## Interface libraries
        add_subdirectory(src/foundation/Config)
        add_subdirectory(src/foundation/Macros)
        ## Normal libraries
        add_subdirectory(src/foundation/Core)
        add_subdirectory(src/scripting/LuaBindings)

        add_subdirectory(src/framework/App)

        if (NOT NIHIL_ENABLE_TESTS)
            add_subdirectory(src/apps/TestApps)
            add_subdirectory(src/apps/SampleApp)
        endif ()

    """.trimIndent()

    private fun linesAfter(text: String, anchor: String) = text.lines().let { it[it.indexOf(anchor) + 1] }

    @Test
    fun `interface library goes at the end of its block`() {
        val r = CMakeRegistration.insert(root, "add_subdirectory(src/foundation/Units2)", "## Interface libraries")!!
        assertEquals("add_subdirectory(src/foundation/Units2)", linesAfter(r.text, "add_subdirectory(src/foundation/Macros)"))
    }

    @Test
    fun `library goes after the normal libraries, before the framework`() {
        val r = CMakeRegistration.insert(root, "add_subdirectory(src/data/Foo)", "## Normal libraries")!!
        assertEquals("add_subdirectory(src/data/Foo)", linesAfter(r.text, "add_subdirectory(src/scripting/LuaBindings)"))
    }

    @Test
    fun `app goes into the indented apps block, with the bundled template's section`() {
        val manifest = ScaffoldTemplates.bundledEntries(ScaffoldKind.entries.single { it.id == "app" }).getValue("template.toml")
        val section = SimpleToml.parse(manifest.lines()).getValue("template")["register_section"] as String
        val r = CMakeRegistration.insert(root, "add_subdirectory(src/apps/Viewer)", section)!!
        assertTrue(r.placed)
        assertEquals("    add_subdirectory(src/apps/Viewer)", linesAfter(r.text, "    add_subdirectory(src/apps/SampleApp)"))
        assertEquals("endif ()", linesAfter(r.text, "    add_subdirectory(src/apps/Viewer)"))
    }

    @Test
    fun `no section means the first block of the file`() {
        val tools = "add_subdirectory(NihilToolsBase)\nadd_subdirectory(NihilLuaDefs)\n"
        assertEquals(
            "add_subdirectory(NihilToolsBase)\nadd_subdirectory(NihilLuaDefs)\nadd_subdirectory(NihilBaker)\n",
            CMakeRegistration.insert(tools, "add_subdirectory(NihilBaker)", null)!!.text,
        )
    }

    @Test
    fun `already registered, or missing section`() {
        assertNull(CMakeRegistration.insert(root, "add_subdirectory(src/foundation/Core)", "## Normal libraries"))
        val r = CMakeRegistration.insert("x\r\n", "add_subdirectory(y)", "## Nope")!!
        assertFalse(r.placed)
        assertEquals("x\r\nadd_subdirectory(y)\r\n", r.text)
    }
}
