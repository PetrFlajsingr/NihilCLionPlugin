package cz.nihil_engine.nihil_utils_plugin.cvars

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleControlProtocolTest {

    @Test
    fun `requests are single line JSON`() {
        assertEquals("""{"cmd":"list"}""", ConsoleControlProtocol.list())
        assertEquals("""{"cmd":"set","name":"r.vsync","value":"false"}""", ConsoleControlProtocol.set("r.vsync", "false"))
        assertEquals("""{"cmd":"exec","line":"r.exposure \"2.0\""}""", ConsoleControlProtocol.exec("r.exposure \"2.0\""))
    }

    @Test
    fun `listing with every kind of object`() {
        val line = """{"type":"list","objects":[""" +
            """{"name":"r.vsync","help":"Enable vertical synchronization","readOnly":false,"kind":"var","value":"true","isBool":true,"componentCount":1,"isFloat":false,"components":[1.0]},""" +
            """{"name":"r.msaa","help":"Samples","readOnly":false,"kind":"var","value":"1","isBool":false,"componentCount":1,"isFloat":false,"components":[1.0],"allowed":["1","2","4","8"]},""" +
            """{"name":"r.dbg_draw.opacity","help":"","readOnly":false,"kind":"var","value":"1","isBool":false,"componentCount":1,"isFloat":true,"components":[1.0],"min":0.0,"max":1.0},""" +
            """{"name":"test.debug_mode","help":"","readOnly":false,"kind":"var","value":"None","isBool":false,"enumNames":["None","GBufferAlbedo"]},""" +
            """{"name":"prof.showcase.gpu.transferSize","help":"","readOnly":false,"kind":"var","value":"4096 KiB","isBool":false,"unit":"KiB","number":4096.0,"integral":true,"min":0.0,"max":65536.0},""" +
            """{"name":"r.demo.tonemap.tint","help":"","readOnly":false,"kind":"var","value":"(1, 1, 1)","isBool":false,"componentCount":3,"isFloat":true,"components":[1.0,0.5,1.0]},""" +
            """{"name":"r.demo.tonemap.exposure_changes","help":"","readOnly":true,"kind":"var","value":"0","isBool":false},""" +
            """{"name":"r.dump_rdg","help":"Dump current render graph","readOnly":false,"kind":"command"},""" +
            """{"name":"weird","kind":"object"},{"help":"no name"}]}"""
        val objects = (ConsoleControlProtocol.parse(line) as ServerMessage.Listing).objects
        assertEquals(9, objects.size)
        val byName = objects.associateBy { it.name }
        val editors = objects.associate { it.name to ValueEditor.forObject(it) }

        assertEquals(ValueEditor.Bool, editors["r.vsync"])
        assertEquals(ValueEditor.Choice(listOf("1", "2", "4", "8")), editors["r.msaa"])
        assertEquals(ValueEditor.Number(0.0, 1.0, integral = false, unit = null), editors["r.dbg_draw.opacity"])
        assertEquals(ValueEditor.Choice(listOf("None", "GBufferAlbedo")), editors["test.debug_mode"])
        assertEquals(ValueEditor.Number(0.0, 65536.0, integral = true, unit = "KiB"), editors["prof.showcase.gpu.transferSize"])
        assertEquals(ValueEditor.Vector(3, integral = false), editors["r.demo.tonemap.tint"])
        assertEquals(ValueEditor.ReadOnly, editors["r.demo.tonemap.exposure_changes"])
        assertEquals(ValueEditor.Command, editors["r.dump_rdg"])
        assertEquals(ValueEditor.ReadOnly, editors["weird"])

        assertEquals("4096", ValueEditing.initialText(byName.getValue("prof.showcase.gpu.transferSize"), editors.getValue("prof.showcase.gpu.transferSize")))
        assertEquals("1,0.5,1", ValueEditing.initialText(byName.getValue("r.demo.tonemap.tint"), editors.getValue("r.demo.tonemap.tint")))
    }

    @Test
    fun `value, output and junk`() {
        val value = ConsoleControlProtocol.parse("""{"type":"value","object":{"name":"r.vsync","kind":"var","value":"false","isBool":true}}""")
        assertEquals("false", (value as ServerMessage.Value).obj.value)
        assertEquals(ServerMessage.Output("r.vsync = false\n"), ConsoleControlProtocol.parse("""{"type":"output","text":"r.vsync = false\n"}"""))
        assertEquals(ServerMessage.Unknown("ping"), ConsoleControlProtocol.parse("""{"type":"ping"}"""))
        assertNull(ConsoleControlProtocol.parse(""))
        assertNull(ConsoleControlProtocol.parse("not json"))
        assertNull(ConsoleControlProtocol.parse("[1,2]"))
    }

    @Test
    fun `validation mirrors what the engine's console accepts`() {
        val ranged = ValueEditor.Number(0.0, 1.0, integral = false, unit = null)
        assertNull(ValueEditing.validate("0.25", ranged))
        assertEquals("Must be at most 1", ValueEditing.validate("2", ranged))
        assertEquals("Not a number", ValueEditing.validate("abc", ranged))
        val whole = ValueEditor.Number(null, null, integral = true, unit = "KiB")
        assertEquals("Must be a whole number", ValueEditing.validate("1.5", whole))
        assertEquals("12", ValueEditing.normalize("12.0", whole))
        val vector = ValueEditor.Vector(3, integral = false)
        assertNull(ValueEditing.validate("1, 0.5, 1", vector))
        assertEquals("1,0.5,1", ValueEditing.normalize("1, 0.5, 1", vector))
        assertEquals("Expected 3 comma separated values", ValueEditing.validate("1,2", vector))
        assertEquals("The console splits values at spaces", ValueEditing.validate("two words", ValueEditor.Text))
        assertTrue(ValueEditing.validate("Solo", ValueEditor.Choice(listOf("All", "Mute"))) != null)
    }

    @Test
    fun `limits read from f32 are shown the way they were written`() {
        assertEquals("0.01", ValueEditing.formatNumber(0.009999999776482582, integral = false))
        assertEquals("16", ValueEditing.formatNumber(16.0, integral = false))
        assertEquals("0.35", ValueEditing.formatNumber(0.3499999940395355, integral = false))
        assertEquals("32", ValueEditing.formatNumber(32.0, integral = true))
    }

    @Test
    fun `set errors are read from the console output`() {
        assertEquals("Error: '9' is not valid", ValueEditing.error("Error: '9' is not valid\n"))
        assertNull(ValueEditing.error("r.msaa = 4\n"))
    }

    @Test
    fun `listening line from the app log`() {
        val m = ConsoleControlProtocol.LISTENING.find("[12:00:01] [CLI] [Info] Console control server listening on port 8344")
        assertEquals("8344", m?.groupValues?.get(1))
        assertEquals("8344", ConsoleControlProtocol.LISTEN_FAILED.find("Console control server failed to listen on port 8344: in use")?.groupValues?.get(1))
    }
}
