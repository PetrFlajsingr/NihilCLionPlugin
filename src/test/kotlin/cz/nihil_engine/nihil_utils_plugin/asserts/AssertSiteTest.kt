package cz.nihil_engine.nihil_utils_plugin.asserts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssertSiteTest {

    private val macros = AssertMacros(AssertKind.entries.map { it.macro } + "NIHIL_CONTAINER_VERIFY_BOUNDS")

    private val source = """
        void f(int x) {
            NIHIL_ENSURE(0xA25E5483, x > 0, LogPlatform, "x must be positive");
            if (!NIHIL_VERIFY(
                    0x0B12C0DE, x < 10, LogPlatform,
                    "x too big: {}", x)) {
                return;
            }
            NIHIL_ENSURE_PARANOID(0x7A31C0E4, x != 3, LogPlatform, "three");
            NIHIL_WARN(0xDEADBEEF, LogPlatform, "warn");
        }
    """.trimIndent()

    @Test
    fun `single line invocation`() {
        val site = AssertSite.findAt(source, 1, macros)!!
        assertEquals("NIHIL_ENSURE", site.macro)
        assertEquals(AssertKind.ENSURE, site.kind)
        assertEquals(0xA25E5483L, site.id)
        assertEquals("0xA25E5483", site.idText)
    }

    @Test
    fun `multi line invocation found from its first, middle or last line`() {
        for (line in 2..4) {
            val site = AssertSite.findAt(source, line, macros)!!
            assertEquals("line $line", AssertKind.VERIFY, site.kind)
            assertEquals(0x0B12C0DEL, site.id)
            assertEquals("0xB12C0DE", site.idText)
            assertEquals(2, site.line)
        }
    }

    @Test
    fun `longer macro names win over their prefixes`() {
        assertEquals("NIHIL_ENSURE_PARANOID", AssertSite.findAt(source, 7, macros)!!.macro)
        assertEquals("NIHIL_WARN", AssertSite.findAt(source, 8, macros)!!.macro)
    }

    @Test
    fun `no invocation nearby`() {
        assertNull(AssertSite.findAt("int main() {\n    return 0;\n}\n", 1, macros))
        assertNull(AssertSite.findAt(source, 99, macros))
    }

    @Test
    fun `wrapper macros come from the names list`() {
        val text = "    NIHIL_CONTAINER_VERIFY_BOUNDS(0x1234, i);\n    NIHIL_CONTAINER_ASSERT(0x5678, b, \"m\");\n"
        val site = AssertSite.findAt(text, 0, macros)!!
        assertEquals("NIHIL_CONTAINER_VERIFY_BOUNDS", site.macro)
        assertEquals(AssertKind.VERIFY, site.kind)
        assertEquals(listOf(0x1234L), AssertSite.findAll(text, macros).map { it.id }) // NIHIL_CONTAINER_ASSERT isn't listed
    }

    @Test
    fun `wrapper kinds follow their names`() {
        assertEquals(AssertKind.VERIFY, AssertKind.of("NIHIL_CONTAINER_SELF_VERIFY_BOUNDS_END_INCLUSIVE"))
        assertEquals(AssertKind.ASSERT, AssertKind.of("NIHIL_WEAK_PTR_LOCK_ASSERT"))
        assertEquals(AssertKind.ASSERT_PARANOID, AssertKind.of("NIHIL_CONTAINER_PARANOID_ASSERT"))
        assertEquals(AssertKind.ENSURE, AssertKind.of("NIHIL_VULKAN_ENSURE"))
        assertEquals(AssertKind.DBG_WARN, AssertKind.of("NIHIL_DBG_WARN"))
    }

    @Test
    fun `names file skips blank lines, comments and duplicates`() {
        assertEquals(listOf("NIHIL_A", "NIHIL_B"), AssertMacros.parse("# x\nNIHIL_A\n\n  NIHIL_B  \nNIHIL_A\n")!!.names)
        assertNull(AssertMacros.parse("\n# only a comment\n"))
    }

    @Test
    fun `fatal flags match IsFatalAssert`() {
        val fatal = AssertKind.entries.filter { it.fatal }.map { it.typeName }.toSet()
        assertEquals(setOf("ParanoidAssert", "Assert", "Verify", "DbgError", "Error"), fatal)
    }

    @Test
    fun `ids parse with and without prefix and leading zeros`() {
        assertEquals(0x0A25E548L, AssertSite.parseId("a25e548"))
        assertEquals(0x0A25E548L, AssertSite.parseId("0x0A25E548"))
        assertEquals(-1L, AssertSite.parseId("FFFFFFFFFFFFFFFF"))
        assertNull(AssertSite.parseId("xyz"))
    }
}
