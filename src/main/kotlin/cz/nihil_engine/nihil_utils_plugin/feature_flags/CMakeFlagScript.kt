package cz.nihil_engine.nihil_utils_plugin.feature_flags

/** One `if`/`elseif`/`else` level around a definition: [expression] must evaluate to [mustBe]. */
data class CMakeGuard(val expression: String, val mustBe: Boolean, val line: Int)

/** `add_compile_definitions(NAME=VALUE)` with the branch conditions that must hold for it to run. */
data class CMakeDefinition(val name: String, val value: String, val line: Int, val guards: List<CMakeGuard>)

/** `option(NAME "help" DEFAULT)`. */
data class CMakeOption(val name: String, val help: String, val default: String, val line: Int)

/**
 * The parts of cmake/NihilFlags.cmake the flag lens needs. Only `if`, `elseif`, `else`, `endif`, `option` and
 * `add_compile_definitions` are understood; everything else is skipped.
 */
class CMakeFlagScript(val definitions: List<CMakeDefinition>, val options: Map<String, CMakeOption>) {

    fun definitionsOf(name: String): List<CMakeDefinition> = definitions.filter { it.name == name }

    /** Flag macros set to NIHIL_ENABLED / NIHIL_DISABLED. */
    val flagNames: Set<String>
        get() = definitions.filter { DefineValue.parse(it.value) != DefineValue.OTHER }.map { it.name }.toSet()

    companion object {
        private val COMMAND = Regex("""(?m)^[ \t]*([A-Za-z_]\w*)[ \t]*\(""")

        fun parse(text: String): CMakeFlagScript {
            val code = stripComments(text)
            val definitions = mutableListOf<CMakeDefinition>()
            val options = linkedMapOf<String, CMakeOption>()

            // Per open if(): the earlier branch expressions (which must all be false) and the current one.
            class Frame(val earlier: MutableList<Pair<String, Int>>, var current: Pair<String, Int>?)
            val stack = ArrayDeque<Frame>()

            fun guards(): List<CMakeGuard> = stack.flatMap { frame ->
                frame.earlier.map { CMakeGuard(it.first, false, it.second) } +
                    listOfNotNull(frame.current?.let { CMakeGuard(it.first, true, it.second) })
            }

            var pos = 0
            while (true) {
                val m = COMMAND.find(code, pos) ?: break
                val open = m.range.last
                val close = matchingParen(code, open)
                if (close < 0) break
                pos = close + 1
                val args = code.substring(open + 1, close).trim()
                val line = code.substring(0, m.range.first).count { it == '\n' } +
                    code.substring(m.range.first, m.groups[1]!!.range.first).count { it == '\n' }
                when (m.groupValues[1].lowercase()) {
                    "if" -> stack.addLast(Frame(mutableListOf(), args to line))
                    "elseif" -> stack.lastOrNull()?.let { f ->
                        f.current?.let { f.earlier += it }
                        f.current = args to line
                    }
                    "else" -> stack.lastOrNull()?.let { f ->
                        f.current?.let { f.earlier += it }
                        f.current = null
                    }
                    "endif" -> stack.removeLastOrNull()
                    "option" -> {
                        val parts = splitArgs(args)
                        if (parts.isNotEmpty()) {
                            val name = parts[0]
                            options[name] = CMakeOption(name, parts.getOrNull(1).orEmpty(), parts.getOrNull(2) ?: "OFF", line)
                        }
                    }
                    "add_compile_definitions" -> for (arg in splitArgs(args)) {
                        val eq = arg.indexOf('=')
                        val name = if (eq < 0) arg else arg.substring(0, eq)
                        if (!name.startsWith("NIHIL_")) continue
                        definitions += CMakeDefinition(name, if (eq < 0) "1" else arg.substring(eq + 1), line, guards())
                    }
                }
            }
            return CMakeFlagScript(definitions, options)
        }

        /** Replaces `# ...` comments outside quoted arguments with spaces, keeping newlines. */
        private fun stripComments(text: String): String {
            val out = StringBuilder(text.length)
            var inString = false
            var k = 0
            while (k < text.length) {
                val c = text[k]
                when {
                    inString && c == '\\' && k + 1 < text.length -> { out.append(c).append(text[k + 1]); k += 2; continue }
                    c == '"' -> { inString = !inString; out.append(c) }
                    !inString && c == '#' -> {
                        while (k < text.length && text[k] != '\n') { out.append(' '); k++ }
                        continue
                    }
                    else -> out.append(c)
                }
                k++
            }
            return out.toString()
        }

        private fun matchingParen(code: String, open: Int): Int {
            var depth = 0
            var inString = false
            var k = open
            while (k < code.length) {
                val c = code[k]
                when {
                    inString && c == '\\' -> k++
                    c == '"' -> inString = !inString
                    !inString && c == '(' -> depth++
                    !inString && c == ')' -> { depth--; if (depth == 0) return k }
                }
                k++
            }
            return -1
        }

        /** Whitespace separated arguments; quotes are removed from quoted ones. */
        fun splitArgs(args: String): List<String> {
            val result = mutableListOf<String>()
            val current = StringBuilder()
            var inString = false
            var quoted = false
            for (c in args) {
                when {
                    c == '"' -> { inString = !inString; quoted = true }
                    !inString && c.isWhitespace() -> {
                        if (current.isNotEmpty() || quoted) result += current.toString()
                        current.clear()
                        quoted = false
                    }
                    else -> current.append(c)
                }
            }
            if (current.isNotEmpty() || quoted) result += current.toString()
            return result
        }
    }
}

/** Where a CMake variable's value came from, for the "why" of a flag value. */
enum class VariableOrigin(val label: String) {
    GENERATION_OPTIONS("profile's CMake options"),
    PROFILE_BUILD_TYPE("profile's build type"),
    CMAKE_CACHE("CMakeCache.txt"),
    OPTION_DEFAULT("option() default"),
}

data class VariableValue(val name: String, val value: String, val origin: VariableOrigin)

object CMakeValues {

    /** CMake's truthiness for a constant, or null for a value that would be read as a variable name. */
    fun truth(value: String): Boolean? {
        val v = value.trim().uppercase()
        return when {
            v in setOf("1", "ON", "YES", "TRUE", "Y") -> true
            v in setOf("0", "OFF", "NO", "FALSE", "N", "IGNORE", "NOTFOUND", "") || v.endsWith("-NOTFOUND") -> false
            v.toDoubleOrNull() != null -> v.toDouble() != 0.0
            else -> null
        }
    }

    /** `-DNAME=VALUE` / `-DNAME:TYPE=VALUE` from already split CMake options; later ones win, as in CMake. */
    fun definesFromOptions(options: List<String>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val pattern = Regex("""^-D([A-Za-z_][\w.+-]*)(?::[A-Za-z]+)?=(.*)$""", RegexOption.DOT_MATCHES_ALL)
        for (option in options) {
            val m = pattern.matchEntire(option.trim()) ?: continue
            result[m.groupValues[1]] = m.groupValues[2].trim().removeSurrounding("\"")
        }
        return result
    }

    /** `NAME:TYPE=VALUE` lines of a CMakeCache.txt. */
    fun parseCache(text: String): Map<String, String> {
        val result = HashMap<String, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd('\r')
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue
            val colon = line.indexOf(':')
            val eq = line.indexOf('=')
            if (colon <= 0 || eq < colon) continue
            result[line.substring(0, colon)] = line.substring(eq + 1)
        }
        return result
    }
}

/**
 * Evaluates `if()` expressions of NihilFlags.cmake with three-valued logic: null means "can't tell", which the
 * lens shows as `?` instead of guessing. Understands NOT, AND, OR, parentheses, constants, variables and STREQUAL.
 */
class CMakeConditionEvaluator(private val lookup: (String) -> VariableValue?) {

    /** Variables read while evaluating, to explain a result. */
    val used = linkedMapOf<String, VariableValue?>()

    fun evaluate(expression: String): Boolean? {
        val tokens = CMakeFlagScript.splitArgs(expression.replace("(", " ( ").replace(")", " ) "))
        if (tokens.isEmpty()) return null
        val parser = Parser(tokens)
        val result = parser.or()
        return if (parser.atEnd()) result else null
    }

    private inner class Parser(private val tokens: List<String>) {
        private var pos = 0

        fun atEnd() = pos >= tokens.size

        private fun peek() = tokens.getOrNull(pos)

        fun or(): Boolean? {
            var value = and()
            while (peek() == "OR") {
                pos++
                val right = and()
                value = when {
                    value == true || right == true -> true
                    value == null || right == null -> null
                    else -> false
                }
            }
            return value
        }

        private fun and(): Boolean? {
            var value = not()
            while (peek() == "AND") {
                pos++
                val right = not()
                value = when {
                    value == false || right == false -> false
                    value == null || right == null -> null
                    else -> true
                }
            }
            return value
        }

        private fun not(): Boolean? {
            if (peek() == "NOT") {
                pos++
                return not()?.not()
            }
            return primary()
        }

        private fun primary(): Boolean? {
            val token = peek() ?: return null
            pos++
            if (token == "(") {
                val inner = or()
                if (peek() == ")") pos++ else return null
                return inner
            }
            if (peek() == "STREQUAL") {
                pos++
                val right = peek() ?: return null
                pos++
                val l = valueOf(token) ?: return null
                val r = valueOf(right) ?: return null
                return l == r
            }
            if (peek() in UNSUPPORTED_BINARY) {
                pos += 2
                return null
            }
            if (token in UNSUPPORTED_UNARY) {
                pos++
                return null
            }
            CMakeValues.truth(token)?.let { return it }
            val variable = lookup(token)
            used[token] = variable
            return variable?.let { CMakeValues.truth(it.value) }
        }

        /** A STREQUAL operand: a variable's value, or the token itself when it's no known variable. */
        private fun valueOf(token: String): String? {
            if (!token.matches(Regex("""[A-Za-z_]\w*"""))) return token
            val variable = lookup(token)
            val looksLikeVariable = token.startsWith("CMAKE_") || token.startsWith("NIHIL_")
            if (variable != null || looksLikeVariable) used[token] = variable
            return variable?.value ?: if (looksLikeVariable) null else token
        }
    }

    companion object {
        private val UNSUPPORTED_BINARY = setOf(
            "EQUAL", "LESS", "GREATER", "LESS_EQUAL", "GREATER_EQUAL", "STRLESS", "STRGREATER", "MATCHES",
            "VERSION_LESS", "VERSION_GREATER", "VERSION_EQUAL", "VERSION_LESS_EQUAL", "VERSION_GREATER_EQUAL", "IN_LIST",
        )
        private val UNSUPPORTED_UNARY = setOf("DEFINED", "EXISTS", "COMMAND", "TARGET", "POLICY", "IS_DIRECTORY", "IS_ABSOLUTE")
    }
}
