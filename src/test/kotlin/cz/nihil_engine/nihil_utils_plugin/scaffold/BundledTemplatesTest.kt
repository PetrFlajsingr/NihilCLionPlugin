package cz.nihil_engine.nihil_utils_plugin.scaffold

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Renders every bundled template with a full variable set, as the dialog does, and checks the result's shape. */
class BundledTemplatesTest {

    private val vars = mapOf(
        "Name" to "SceneGraph", "name_lower" to "scenegraph", "NAME_UPPER" to "SCENEGRAPH",
        "module" to "scenegraph", "module_file" to "scenegraph", "namespace" to "nihil::scene", "group" to "data",
        "description" to "Scene graph", "author" to "Tester", "year" to "2026",
        "public_dependencies" to "NihilEngine::Core\nNihilEngine::Common", "private_dependencies" to "NihilEngine::AllocStatics",
        "dependencies" to "NihilEngine::Common\nNihilEngine::AllocStatics",
    )

    private fun render(kind: String, flags: Set<String>): Map<String, String> {
        val entries = ScaffoldTemplates.bundledEntries(ScaffoldKind.entries.single { it.id == kind })
        assertTrue("$kind has a manifest", "template.toml" in entries)
        return entries.filterKeys { it.startsWith("files/") }.entries.associate { (key, content) ->
            val path = TemplateRenderer.renderPath(key.removePrefix("files/"), vars)
            path to TemplateRenderer.render(content, vars + ("file_name" to path.substringAfterLast('/')), flags)
        }.filterValues { it.isNotBlank() }
    }

    @Test
    fun `library without profiling`() {
        val files = render("library", setOf("has_public_dependencies", "has_private_dependencies"))
        assertEquals(
            setOf(
                "CMakeLists.txt", "Private/Sources/log_categories.cpp", "Public/NihilSceneGraph/config.hpp",
                "Public/NihilSceneGraph/log_categories.ixx", "Public/NihilSceneGraph/scenegraph.ixx",
                "config/config_debug.hpp", "config/config_development.hpp", "config/config_profiling.hpp",
                "config/config_release.hpp", "config/config_test.hpp",
            ),
            files.keys,
        )
        val cmake = files.getValue("CMakeLists.txt")
        assertTrue(cmake, "        PUBLIC_DEPENDENCIES\n        NihilEngine::Core\n        NihilEngine::Common\n" in cmake)
        assertFalse(cmake, "prof_categories" in cmake)
        assertTrue("export module nihil.scenegraph;" in files.getValue("Public/NihilSceneGraph/scenegraph.ixx"))
        assertTrue("NIHILSCENEGRAPH_EXPORT extern log::Category LogSceneGraph;" in files.getValue("Public/NihilSceneGraph/log_categories.ixx"))
    }

    @Test
    fun `library with profiling`() {
        val files = render("library", setOf("profiling", "has_public_dependencies", "has_private_dependencies"))
        assertTrue("Public/NihilSceneGraph/prof_categories.ixx" in files)
        assertTrue("export import :prof_categories;" in files.getValue("Public/NihilSceneGraph/scenegraph.ixx"))
        assertTrue("Category SceneGraph{" in files.getValue("Private/Sources/log_categories.cpp"))
    }

    @Test
    fun `other kinds render`() {
        assertTrue("NIHIL_APP_MAIN(nihil::SceneGraph" in render("app", emptySet()).getValue("main.cpp"))
        assertTrue("NIHIL_TOOL_MAIN(nihil::tools::SceneGraphExe" in render("tool", setOf("has_dependencies")).getValue("main.cpp"))
        assertTrue("Public/NihilSceneGraph/SceneGraph.hpp" in render("interface_library", emptySet()))
        assertTrue("import nihil.scenegraph;" in render("library_test", emptySet()).getValue("SceneGraphTests.cpp"))
    }
}
