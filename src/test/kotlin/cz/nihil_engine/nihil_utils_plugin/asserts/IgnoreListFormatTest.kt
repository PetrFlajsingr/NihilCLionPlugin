package cz.nihil_engine.nihil_utils_plugin.asserts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IgnoreListFormatTest {

    @Test
    fun `parses ids and skips other lines`() {
        assertEquals(setOf(0xA25E5483L, 0x1B7C0DE5L), IgnoreListFormat.parse("0xA25E5483\n# note\n  0x1b7c0de5 \n\n"))
    }

    @Test
    fun `adds once, keeping line endings`() {
        assertEquals("0xA25E5483\n", IgnoreListFormat.withAdded("", 0xA25E5483L))
        assertEquals("0x1\r\n0xA25E5483\r\n", IgnoreListFormat.withAdded("0x1\r\n", 0xA25E5483L))
        assertEquals("0x1\n0xA25E5483\n", IgnoreListFormat.withAdded("0x1", 0xA25E5483L))
        assertNull(IgnoreListFormat.withAdded("0xa25e5483\n", 0xA25E5483L))
    }

    @Test
    fun `removes every line of that id, keeps the rest`() {
        assertEquals("# note\n0x2\n", IgnoreListFormat.withRemoved("0x1\n# note\n0x01\n0x2\n", 0x1L))
        assertEquals("0x2\r\n", IgnoreListFormat.withRemoved("0x1\r\n0x2\r\n", 0x1L))
        assertEquals("", IgnoreListFormat.withRemoved("0x1\n", 0x1L))
    }
}
