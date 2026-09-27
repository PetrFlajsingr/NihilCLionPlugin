package cz.nihil_engine.nihil_utils_plugin.cvars

import cz.nihil_engine.nihil_utils_plugin.util.CppText

enum class CVarKind(val label: String) {
    VARIABLE("cvar"),
    COMMAND("command"),
    /** `struct [[= $cvar{"r.demo"}]] Settings`: every reflected member becomes `r.demo.<member>`. */
    PREFIX("cvar prefix"),
}

/**
 * A console object declared with a literal name. [start] is where the declaration starts (`cvar::Auto`,
 * `createConsoleCommand`, `struct`), [nameOffset] where the name literal's text starts.
 */
data class CVarDecl(val name: String, val kind: CVarKind, val start: Int, val nameOffset: Int, val help: String?)

/**
 * Finds cvar and console command declarations in C++ text:
 *
 * - `cvar::Auto<T> name{"r.vsync", "help", ...}` / `cvar::Auto name{...}` / `AutoConsoleVariable<T> name{...}`,
 *   also with `(` and with the literal on the next line
 * - `AutoConsoleCommand name{"r.dbg_draw.subgroup.mode", "help", ...}`
 * - `createConsoleCommand("r.dump_rdg", "help", ...)`
 * - `struct [[= $cvar{"r.demo"}, ...]] DemoRenderSettings` (the prefix only)
 *
 * A leading `memtrack::SomeTag` or `ExternalStorage<T>{...}` argument is skipped. Names that aren't a literal
 * (`Format("r.dbg_draw.channel.{}", name)`, a variable) are built at runtime and can't be known from text.
 */
object CVarScanner {

    private val VARIABLE_START = Regex("""\b(?:cvar::Auto|AutoConsoleVariable)\b""")
    private val COMMAND_OBJECT = Regex("""\bAutoConsoleCommand\s+[A-Za-z_]\w*\s*[{(]""")
    private val CREATE_COMMAND = Regex("""\bcreateConsoleCommand\s*\(""")
    private val ANNOTATED_TYPE = Regex("""\b(?:struct|class)\s*\[\[""")
    private val PREFIX_ANNOTATION = Regex("""\${'$'}cvar\s*\{\s*"((?:\\.|[^"\\])*)"\s*}""")
    private val IDENTIFIER = Regex("""[A-Za-z_]\w*""")
    private val SKIPPABLE_ARG = Regex("""^(?:memtrack::\w+|(?:\w+::)*ExternalStorage\b.*)$""", RegexOption.DOT_MATCHES_ALL)

    /** Cheap pre-check so indexing skips files that can't declare anything. */
    fun mayDeclare(text: CharSequence): Boolean =
        text.contains("cvar::Auto") || text.contains("AutoConsoleVariable") || text.contains("ConsoleCommand") || text.contains("\$cvar")

    fun scan(text: CharSequence): List<CVarDecl> {
        if (!mayDeclare(text)) return emptyList()
        val code = CppText.stripComments(text)
        val result = mutableListOf<CVarDecl>()

        for (m in VARIABLE_START.findAll(code)) {
            var k = skipSpaces(code, m.range.last + 1)
            if (k < code.length && code[k] == '<') {
                k = skipAngles(code, k) ?: continue
                k = skipSpaces(code, k)
            }
            val id = IDENTIFIER.matchAt(code, k) ?: continue
            k = skipSpaces(code, id.range.last + 1)
            if (k >= code.length || (code[k] != '{' && code[k] != '(')) continue
            declaration(code, m.range.first, k, CVarKind.VARIABLE)?.let { result += it }
        }
        for (m in COMMAND_OBJECT.findAll(code)) declaration(code, m.range.first, m.range.last, CVarKind.COMMAND)?.let { result += it }
        for (m in CREATE_COMMAND.findAll(code)) declaration(code, m.range.first, m.range.last, CVarKind.COMMAND)?.let { result += it }

        for (m in ANNOTATED_TYPE.findAll(code)) {
            val close = code.indexOf("]]", m.range.last + 1).takeIf { it >= 0 } ?: continue
            val p = PREFIX_ANNOTATION.find(code.substring(m.range.last + 1, close)) ?: continue
            val name = CppText.unescape(p.groupValues[1])
            if (isValidName(name)) result += CVarDecl(name, CVarKind.PREFIX, m.range.first, m.range.last + 1 + p.groups[1]!!.range.first, null)
        }
        return result.sortedBy { it.start }
    }

    /** Parses the arguments after [open] (`{` or `(`) and returns the declaration when the name is a literal. */
    private fun declaration(code: String, start: Int, open: Int, kind: CVarKind): CVarDecl? {
        val args = arguments(code, open, limit = 4)
        var index = 0
        while (index < args.size - 1 && index < 2 && SKIPPABLE_ARG.matches(args[index].text)) index++
        val nameArg = args.getOrNull(index) ?: return null
        val name = literal(nameArg.text) ?: return null
        if (!isValidName(name)) return null
        val help = args.getOrNull(index + 1)?.let { literal(it.text) }
        return CVarDecl(name, kind, start, nameArg.offset + nameArg.text.indexOf('"') + 1, help)
    }

    private fun isValidName(name: String) = name.isNotEmpty() && name.none { it.isWhitespace() }

    private data class Arg(val text: String, val offset: Int)

    /** Up to [limit] top-level arguments, trimmed, with their start offsets. */
    private fun arguments(code: String, open: Int, limit: Int): List<Arg> {
        val closeChar = if (code[open] == '{') '}' else ')'
        val args = mutableListOf<Arg>()
        var depth = 0
        var argStart = open + 1
        var k = open + 1
        fun addArg(end: Int) {
            val raw = code.substring(argStart, end)
            val lead = raw.length - raw.trimStart().length
            if (raw.isNotBlank()) args += Arg(raw.trim(), argStart + lead)
        }
        while (k < code.length && args.size < limit) {
            val c = code[k]
            when {
                c == '"' || c == '\'' -> {
                    k++
                    while (k < code.length && code[k] != c) { if (code[k] == '\\') k++; k++ }
                }
                c == '(' || c == '{' || c == '[' -> depth++
                (c == ')' || c == '}' || c == ']') && depth > 0 -> depth--
                c == closeChar && depth == 0 -> { addArg(k); return args }
                c == ',' && depth == 0 -> { addArg(k); argStart = k + 1 }
                c == ';' && depth == 0 -> return args
            }
            k++
        }
        return args
    }

    /** The value of an argument that is only string literal(s), adjacent literals concatenated. */
    private fun literal(arg: String): String? {
        if (!arg.startsWith('"')) return null
        val sb = StringBuilder()
        var k = 0
        while (k < arg.length) {
            if (arg[k].isWhitespace()) { k++; continue }
            if (arg[k] != '"') return null
            k++
            val begin = k
            while (k < arg.length && arg[k] != '"') { if (arg[k] == '\\') k++; k++ }
            if (k >= arg.length) return null
            sb.append(CppText.unescape(arg.substring(begin, k)))
            k++
        }
        return sb.toString()
    }

    private fun skipSpaces(code: String, from: Int): Int {
        var k = from
        while (k < code.length && code[k].isWhitespace()) k++
        return k
    }

    /** Index after the `>` matching the `<` at [open], or null when it doesn't close within a declaration. */
    private fun skipAngles(code: String, open: Int): Int? {
        var depth = 0
        var k = open
        while (k < code.length) {
            when (code[k]) {
                '<' -> depth++
                '>' -> { depth--; if (depth == 0) return k + 1 }
                ';', '{', '}' -> return null
            }
            k++
        }
        return null
    }
}
