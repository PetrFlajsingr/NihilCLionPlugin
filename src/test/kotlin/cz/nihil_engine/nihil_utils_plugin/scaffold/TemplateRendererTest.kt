package cz.nihil_engine.nihil_utils_plugin.scaffold

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TemplateRendererTest {

    private val vars = mapOf("Name" to "DebugDraw", "deps" to "NihilEngine::Core\nNihilEngine::Common", "none" to "")

    @Test
    fun `inline placeholders, CMake variables untouched`() {
        assertEquals(
            "nihil_library(NAME DebugDraw SOURCES \${SOURCES})",
            TemplateRenderer.render("nihil_library(NAME {{Name}} SOURCES \${SOURCES})", vars, emptySet()),
        )
    }

    @Test
    fun `placeholder alone on a line indents every value line, or drops the line`() {
        val template = "DEPS\n        {{deps}}\n        {{none}}\nEND"
        assertEquals("DEPS\n        NihilEngine::Core\n        NihilEngine::Common\nEND", TemplateRenderer.render(template, vars, emptySet()))
    }

    @Test
    fun `if and unless blocks, nested`() {
        val template = "a\n{{#if prof}}\nb\n  {{#unless test}}\n  c\n  {{/unless}}\n{{/if}}\nd"
        assertEquals("a\nb\n  c\nd", TemplateRenderer.render(template, vars, setOf("prof")))
        assertEquals("a\nb\nd", TemplateRenderer.render(template, vars, setOf("prof", "test")))
        assertEquals("a\nd", TemplateRenderer.render(template, vars, emptySet()))
    }

    @Test
    fun `whole file can be conditional`() {
        assertEquals("", TemplateRenderer.render("{{#if prof}}\nx\n{{/if}}", vars, emptySet()).trim())
    }

    @Test
    fun `errors name the problem`() {
        assertThrows(TemplateRenderer.TemplateException::class.java) { TemplateRenderer.render("{{Nme}}", vars, emptySet()) }
        assertThrows(TemplateRenderer.TemplateException::class.java) { TemplateRenderer.render("{{#if x}}\na", vars, emptySet()) }
        assertThrows(TemplateRenderer.TemplateException::class.java) { TemplateRenderer.render("{{/if}}", vars, emptySet()) }
    }

    @Test
    fun `paths`() {
        assertEquals("Public/NihilDebugDraw/x.ixx", TemplateRenderer.renderPath("Public/Nihil{{Name}}/x.ixx", vars))
    }
}
