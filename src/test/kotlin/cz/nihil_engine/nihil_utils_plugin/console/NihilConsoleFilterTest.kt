package cz.nihil_engine.nihil_utils_plugin.console

import com.intellij.openapi.project.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.reflect.Proxy

class NihilConsoleFilterTest {

    private val project = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Project::class.java)) { _, _, _ -> null } as Project

    /** Feeds lines like a console does and returns the linked text per line. */
    private fun links(vararg lines: String): List<List<String>> {
        val filter = NihilConsoleFilter(project)
        val doc = StringBuilder()
        return lines.map { raw ->
            val line = "$raw\n"
            doc.append(line)
            val result = filter.applyFilter(line, doc.length)
            result?.resultItems.orEmpty().map { doc.substring(it.highlightStartOffset, it.highlightEndOffset) }
        }
    }

    @Test
    fun `assert report block`() {
        val result = links(
            "[2026-09-04 18:39:11.1210655] [Render] [Error] Ensure a25e5483 failed",
            "expression:",
            "    'x > 0'",
            "file:",
            "    'F:/CLionProjects/NihilEngine/src/Renderer.cpp'",
            "function:",
            "    'void f(int)'",
            "line:",
            "    '120'",
        )
        assertEquals(listOf("a25e5483"), result[0])
        assertEquals(emptyList<String>(), result[2])
        assertEquals(listOf("F:/CLionProjects/NihilEngine/src/Renderer.cpp"), result[4])
        assertEquals(emptyList<String>(), result[6])
        assertEquals(listOf("120"), result[8])
    }

    @Test
    fun `stack trace frames`() {
        val result = links(
            "stacktrace:",
            "0> F:\\CLionProjects\\NihilEngine\\src\\Renderer.cpp(120): NihilRender!nihil::Renderer::draw+0x1F",
            "1> C:/Windows/System32/KERNEL32.DLL: KERNEL32!BaseThreadInitThunk+0x14",
        )
        assertEquals(listOf("F:\\CLionProjects\\NihilEngine\\src\\Renderer.cpp(120)"), result[1])
        assertEquals(emptyList<String>(), result[2])
    }

    @Test
    fun `unrelated output`() {
        val filter = NihilConsoleFilter(project)
        assertNull(filter.applyFilter("Build failed with 3 errors\n", 27))
        assertNull(filter.applyFilter("Assertion deadbeef failed\n", 26))
    }
}
