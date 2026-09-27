package cz.nihil_engine.nihil_utils_plugin.commit_checks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TestSelectionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val targets = listOf("NihilTestCommon", "NihilTestRegex", "NihilTestRDG_vulkan", "NihilTestRDG_d3d12", "NihilTestCommonExtras")

    private fun tree(): File {
        val root = tmp.root
        fun write(path: String, text: String = "") = File(root, path).apply { parentFile.mkdirs(); writeText(text) }
        write("src/foundation/CMakeLists.txt", "add_subdirectory(Common)")
        write("src/foundation/Common/CMakeLists.txt", "nihil_library(\n NAME\n Common\n SOURCES a.cpp)\nnihil_library(NAME AllocStatics STATIC)")
        write("src/foundation/Common/Public/NihilCommon/Vector.ixx")
        write("src/render/RDG/CMakeLists.txt", "nihil_library(NAME RDG)")
        write("src/render/RDG/Private/Graph.cpp")
        write("src/apps/Game/main.cpp")
        write("src/tests/LoggerStartup.cpp")
        write("src/tests/NihilCommon/Vector.cpp")
        write("src/tests/NihilRegex/regex.cpp")
        return root
    }

    private fun select(root: File, vararg paths: String, overrides: Map<String, List<String>> = emptyMap()) =
        TestSelection.select(root, paths.map { File(root, it) }, targets, overrides)

    @Test
    fun `a library source selects its tests`() {
        val root = tree()
        val selected = select(root, "src/foundation/Common/Public/NihilCommon/Vector.ixx")
        assertEquals(listOf("Common"), selected.map { it.library })
        assertEquals(listOf("NihilTestCommon"), selected.single().targets)
        assertEquals("NihilCommon", selected.single().displayName)
    }

    @Test
    fun `libraries without tests and files outside libraries select nothing`() {
        val root = tree()
        val touched = TestSelection.librariesTouched(root, listOf(File(root, "src/foundation/Common/a.cpp"), File(root, "src/apps/Game/main.cpp")))
        assertEquals(setOf("Common", "AllocStatics"), touched.libraries)
        assertEquals(listOf("Common"), select(root, "src/foundation/Common/a.cpp", "src/apps/Game/main.cpp").map { it.library })
    }

    @Test
    fun `variant targets belong to their library`() {
        val root = tree()
        assertEquals(listOf("NihilTestRDG_d3d12", "NihilTestRDG_vulkan"), select(root, "src/render/RDG/Private/Graph.cpp").single().targets)
    }

    @Test
    fun `a test source selects its own library`() {
        assertEquals(listOf("Regex"), select(tree(), "src/tests/NihilRegex/regex.cpp").map { it.library })
    }

    @Test
    fun `a shared test file selects every test`() {
        val root = tree()
        val touched = TestSelection.librariesTouched(root, listOf(File(root, "src/tests/LoggerStartup.cpp")))
        assertTrue(touched.allTests)
        assertEquals(listOf("Common", "CommonExtras", "RDG", "Regex"), select(root, "src/tests/LoggerStartup.cpp").map { it.library })
    }

    @Test
    fun `a deleted file still maps to its library`() {
        assertEquals(listOf("Common"), select(tree(), "src/foundation/Common/Private/Gone.cpp").map { it.library })
    }

    @Test
    fun `toml overrides the convention`() {
        val root = tree()
        val overrides = mapOf("RDG" to listOf("NihilTestRDG_vulkan"), "Common" to emptyList())
        assertEquals(listOf("NihilTestRDG_vulkan"), select(root, "src/render/RDG/Private/Graph.cpp", overrides = overrides).single().targets)
        assertTrue(select(root, "src/foundation/Common/a.cpp", overrides = overrides).isEmpty())
    }

    @Test
    fun `inputs cover the library, its tests and the shared test files`() {
        val root = tree()
        val inputs = select(root, "src/tests/NihilCommon/Vector.cpp").single().inputs.map { it.relativeTo(root.canonicalFile).invariantSeparatorsPath }
        assertEquals(listOf("src/foundation/Common", "src/tests/NihilCommon", "src/tests/LoggerStartup.cpp"), inputs)
    }

    @Test
    fun `a target two libraries share runs once, on the union of their inputs`() {
        val rhi = File("src/render/RHI")
        val vulkan = File("src/render/RHI/Backends/Vulkan")
        val shared = File("src/tests/LoggerStartup.cpp")
        val selection = listOf(
            LibraryTests("RHI", listOf("NihilTestRHI_d3d12", "NihilTestRHI_vulkan"), listOf(rhi, File("src/tests/NihilRHI"), shared)),
            LibraryTests("RHI.Vulkan", listOf("NihilTestRHI_vulkan"), listOf(vulkan, shared)),
        )
        val union = listOf(rhi, vulkan, File("src/tests/NihilRHI"), shared).sortedBy { it.path }

        val inputs = TestSelection.inputsByTarget(selection)
        assertEquals(listOf("NihilTestRHI_d3d12", "NihilTestRHI_vulkan"), inputs.keys.toList())
        assertEquals(selection[0].inputs.sortedBy { it.path }, inputs.getValue("NihilTestRHI_d3d12"))
        assertEquals(union, inputs.getValue("NihilTestRHI_vulkan"))
        // Stable whichever library comes first, so the cache key doesn't depend on selection order
        assertEquals(union, TestSelection.inputsByTarget(selection.reversed()).getValue("NihilTestRHI_vulkan"))
    }

    @Test
    fun `library of a target`() {
        assertEquals("RDG", TestSelection.libraryOf("NihilTestRDG_vulkan", emptyMap()))
        assertEquals("Common", TestSelection.libraryOf("NihilTestCommon", emptyMap()))
        assertEquals("Custom", TestSelection.libraryOf("OddName", mapOf("Custom" to listOf("OddName"))))
        assertEquals(null, TestSelection.libraryOf("Other", emptyMap()))
    }

    @Test
    fun `NihilTestCommon doesn't claim NihilTestCommonExtras`() {
        assertFalse("NihilTestCommonExtras" in TestSelection.targetsFor("Common", targets, emptyMap()))
    }
}
