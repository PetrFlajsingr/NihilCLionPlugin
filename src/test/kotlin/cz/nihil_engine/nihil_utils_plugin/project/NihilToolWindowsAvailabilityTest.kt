package cz.nihil_engine.nihil_utils_plugin.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A feature turned on in `.idea/nihil_plugin.toml` while the project is open has to show its tool window. The factory
 * only asks when the project opens, so the switch is a project listener: it has to be registered, and know every window.
 */
class NihilToolWindowsAvailabilityTest {

    private val pluginXml = javaClass.classLoader.getResourceAsStream("META-INF/plugin.xml")!!.use {
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it).documentElement
    }

    private fun elements(tag: String): List<Element> =
        pluginXml.getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

    @Test
    fun `listens to config changes`() {
        val listener = elements("listener").filter { it.getAttribute("class") == NihilToolWindowsAvailability::class.java.name }
        assertEquals(listOf(NihilProjectConfigService.Listener::class.java.name), listener.map { it.getAttribute("topic") })
        assertEquals("projectListeners", (listener.single().parentNode as Element).tagName)
    }

    @Test
    fun `covers every tool window`() {
        val ids = elements("toolWindow").map { it.getAttribute("id") }
        assertTrue(ids.isNotEmpty())
        assertEquals(ids.toSet(), NihilToolWindowsAvailability.TOOL_WINDOWS.keys)
    }
}
