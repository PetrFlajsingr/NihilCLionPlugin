package cz.nihil_engine.nihil_utils_plugin.cvars

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** A console object as the app's ConsoleControlServer reports it (BuildObjectEntry in ConsoleControlServer.cpp). */
data class LiveObject(
    val name: String,
    val help: String = "",
    val readOnly: Boolean = false,
    /** "var", "command" or "object". */
    val kind: String = "var",
    val value: String? = null,
    val isBool: Boolean = false,
    val min: Double? = null,
    val max: Double? = null,
    /** Values of a set limiter. */
    val allowed: List<String> = emptyList(),
    val enumNames: List<String> = emptyList(),
    /** Quantity cvars: unit suffix, value in that unit, and whether it's integral. */
    val unit: String? = null,
    val number: Double? = null,
    val integral: Boolean? = null,
    val componentCount: Int = 1,
    val isFloat: Boolean? = null,
    val components: List<Double> = emptyList(),
) {
    val isVariable: Boolean get() = kind == "var"
    val isCommand: Boolean get() = kind == "command"
}

sealed interface ServerMessage {
    data class Listing(val objects: List<LiveObject>) : ServerMessage
    data class Value(val obj: LiveObject) : ServerMessage
    data class Output(val text: String) : ServerMessage
    data class Unknown(val type: String) : ServerMessage
}

/** Newline-delimited JSON, as spoken by ConsoleControlServer.cpp and `.claude/scripts/console-control.py`. */
object ConsoleControlProtocol {

    const val DEFAULT_PORT = 8344

    /** The line the app logs once its server listens: `Console control server listening on port 8344`. */
    val LISTENING = Regex("""Console control server listening on port (\d+)""")
    val LISTEN_FAILED = Regex("""Console control server failed to listen on port (\d+)""")

    fun list(): String = JsonObject().apply { addProperty("cmd", "list") }.toString()

    fun set(name: String, value: String): String = JsonObject().apply {
        addProperty("cmd", "set")
        addProperty("name", name)
        addProperty("value", value)
    }.toString()

    fun exec(line: String): String = JsonObject().apply {
        addProperty("cmd", "exec")
        addProperty("line", line)
    }.toString()

    /** Null for a blank line or one that isn't a JSON object. */
    fun parse(line: String): ServerMessage? {
        if (line.isBlank()) return null
        val json = runCatching { JsonParser.parseString(line) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        return when (val type = json.string("type")) {
            "list" -> ServerMessage.Listing(json.getAsJsonArray("objects")?.mapNotNull { it.asObjectOrNull()?.let(::toObject) }.orEmpty())
            "value" -> json.get("object")?.asObjectOrNull()?.let(::toObject)?.let { ServerMessage.Value(it) }
            "output" -> ServerMessage.Output(json.string("text").orEmpty())
            else -> ServerMessage.Unknown(type.orEmpty())
        }
    }

    private fun toObject(o: JsonObject): LiveObject? {
        val name = o.string("name")?.takeIf { it.isNotEmpty() } ?: return null
        return LiveObject(
            name = name,
            help = o.string("help").orEmpty(),
            readOnly = o.bool("readOnly") ?: false,
            kind = o.string("kind") ?: "var",
            value = o.get("value")?.takeUnless { it.isJsonNull }?.let { if (it.isJsonPrimitive) it.asString else it.toString() },
            isBool = o.bool("isBool") ?: false,
            min = o.double("min"),
            max = o.double("max"),
            allowed = o.strings("allowed"),
            enumNames = o.strings("enumNames"),
            unit = o.string("unit"),
            number = o.double("number"),
            integral = o.bool("integral"),
            componentCount = o.double("componentCount")?.toInt() ?: 1,
            isFloat = o.bool("isFloat"),
            components = o.getAsJsonArray("components")?.mapNotNull { runCatching { it.asDouble }.getOrNull() }.orEmpty(),
        )
    }

    private fun JsonElement.asObjectOrNull(): JsonObject? = if (isJsonObject) asJsonObject else null
    private fun JsonObject.string(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.bool(key: String): Boolean? = get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asBoolean }.getOrNull() }
    private fun JsonObject.double(key: String): Double? = get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asDouble }.getOrNull() }
    private fun JsonObject.strings(key: String): List<String> =
        getAsJsonArray(key)?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString }.orEmpty()
}

/** How a live object is edited, and how an edited value is checked before it's sent. */
sealed interface ValueEditor {
    data object ReadOnly : ValueEditor
    data object Command : ValueEditor
    data object Bool : ValueEditor
    data class Choice(val options: List<String>) : ValueEditor
    data class Number(val min: Double?, val max: Double?, val integral: Boolean, val unit: String?) : ValueEditor
    /** glm vectors: the engine wants `x,y,z` with no spaces. */
    data class Vector(val count: Int, val integral: Boolean) : ValueEditor
    data object Text : ValueEditor

    companion object {
        fun forObject(o: LiveObject): ValueEditor = when {
            o.isCommand -> Command
            !o.isVariable || o.readOnly -> ReadOnly
            o.isBool -> Bool
            o.enumNames.isNotEmpty() -> Choice(o.enumNames)
            o.allowed.isNotEmpty() -> Choice(o.allowed)
            o.componentCount > 1 -> Vector(o.componentCount, o.isFloat == false)
            o.unit != null || o.number != null -> Number(o.min, o.max, o.integral == true, o.unit?.takeIf { it.isNotEmpty() })
            o.isFloat != null -> Number(o.min, o.max, o.isFloat == false, null)
            else -> Text
        }
    }
}

object ValueEditing {

    /** What the editor shows first: the unit-less number of a quantity, `x,y,z` of a vector, else the value. */
    fun initialText(o: LiveObject, editor: ValueEditor): String = when (editor) {
        is ValueEditor.Number -> o.number?.let { formatNumber(it, editor.integral) }
            ?: o.value?.removeSuffix(editor.unit.orEmpty())?.trim().orEmpty()
        is ValueEditor.Vector -> if (o.components.size == editor.count) o.components.joinToString(",") { formatNumber(it, editor.integral) } else o.value.orEmpty()
        else -> o.value.orEmpty()
    }

    /** Whole numbers without `.0`; others to 7 significant digits, so an f32 limit of 0.01 doesn't show as 0.009999999776. */
    fun formatNumber(value: Double, integral: Boolean): String = when {
        integral || (value == Math.rint(value) && kotlin.math.abs(value) < 1e15) -> value.toLong().toString()
        else -> java.math.BigDecimal(value).round(java.math.MathContext(7)).stripTrailingZeros().toPlainString()
    }

    /** Null when [text] can be sent, else why not. */
    fun validate(text: String, editor: ValueEditor): String? {
        val t = text.trim()
        return when (editor) {
            is ValueEditor.Number -> {
                val v = t.toDoubleOrNull() ?: return "Not a number"
                if (editor.integral && v != Math.rint(v)) return "Must be a whole number"
                if (editor.min != null && v < editor.min) return "Must be at least ${formatNumber(editor.min, editor.integral)}"
                if (editor.max != null && v > editor.max) return "Must be at most ${formatNumber(editor.max, editor.integral)}"
                null
            }
            is ValueEditor.Vector -> {
                val parts = t.split(',').map { it.trim() }
                if (parts.size != editor.count) return "Expected ${editor.count} comma separated values"
                if (parts.any { it.toDoubleOrNull() == null }) return "Every component must be a number"
                if (editor.integral && parts.any { it.toDouble() != Math.rint(it.toDouble()) }) return "Components must be whole numbers"
                null
            }
            is ValueEditor.Choice -> if (t in editor.options) null else "Must be one of ${editor.options.joinToString(", ")}"
            ValueEditor.Bool -> if (t in setOf("true", "false", "1", "0")) null else "Must be true or false"
            ValueEditor.Text -> if (t.isEmpty()) "Empty value" else if (t.any { it.isWhitespace() }) "The console splits values at spaces" else null
            ValueEditor.ReadOnly -> "Read-only"
            ValueEditor.Command -> null
        }
    }

    /** The value string to send for validated [text]: spaces removed from vectors, integers without `.0`. */
    fun normalize(text: String, editor: ValueEditor): String {
        val t = text.trim()
        return when (editor) {
            is ValueEditor.Vector -> t.split(',').joinToString(",") { it.trim() }
            is ValueEditor.Number -> if (editor.integral) t.toDouble().toLong().toString() else t
            else -> t
        }
    }

    /** The console's reply to a set, when it reports a failure (`Error: ...`). */
    fun error(output: String): String? = output.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("Error:") }
}
