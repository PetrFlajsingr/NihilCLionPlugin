package cz.nihil_engine.nihil_utils_plugin.feature_flags

import cz.nihil_engine.nihil_utils_plugin.util.CppText

enum class DefineValue {
    ENABLED, DISABLED,
    /** Anything else, e.g. an alias of another flag; shown as unknown with the raw text. */
    OTHER;

    companion object {
        fun parse(raw: String): DefineValue = when (raw.trim()) {
            "NIHIL_ENABLED" -> ENABLED
            "NIHIL_DISABLED" -> DISABLED
            else -> OTHER
        }
    }
}

/** An enclosing `#if`/`#ifdef`/`#ifndef`/`#elif` (as written) or the `#else` of one. */
data class PpCondition(val directive: String, val expression: String) {
    val text: String get() = if (directive == "else") "#else of $expression" else "#$directive $expression"
}

/**
 * `#define NIHIL_X <value>` whose value is NIHIL_ENABLED, NIHIL_DISABLED or something the plugin can't evaluate.
 * [guarded]: inside `#ifndef NIHIL_X`, so it's a default a build can override. [conditions]: the other enclosing
 * preprocessor conditions, outermost first.
 */
data class FlagDefine(
    val name: String,
    val value: DefineValue,
    val rawValue: String,
    val line: Int,
    val guarded: Boolean,
    val conditions: List<PpCondition>,
)

/** `inline constexpr Feature Name{"description", value};` from a config header. */
data class FeatureDecl(val name: String, val description: String, val value: String, val line: Int)

/** A `NIHIL_IS_ENABLED(X)` use: [start]..[end] is the whole macro call. */
data class FlagUse(val name: String, val start: Int, val end: Int)

object CppFlagScanner {

    private val DIRECTIVE = Regex("""^\s*#\s*(\w+)\s*(.*?)\s*$""")
    private val DEFINE = Regex("""^(NIHIL_\w+)(?:\s+(.*?))?\s*$""")
    private val FEATURE = Regex(
        """\b(?:inline\s+)?(?:constexpr\s+)?Feature\s+(\w+)\s*\{\s*"((?:\\.|[^"\\])*)"\s*,\s*(.+?)\s*}\s*;""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val USE = Regex("""\bNIHIL_IS_ENABLED\s*\(\s*([A-Za-z_]\w*)\s*\)""")

    /** Cheap pre-check so indexing skips files that can't define a flag or a Feature constant. */
    fun mayDeclare(text: CharSequence): Boolean =
        text.contains("NIHIL_") && text.contains("define") || text.contains("Feature")

    /**
     * Defines of `NIHIL_*` macros whose value is a single token, i.e. everything that can be a feature flag.
     * Function-like macros and multi-token values are skipped.
     */
    fun defines(text: CharSequence): List<FlagDefine> {
        val lines = CppText.stripComments(text).lines()
        val result = mutableListOf<FlagDefine>()
        // One frame per open #if: the condition of the branch we're in.
        val stack = ArrayDeque<PpCondition>()
        var k = 0
        while (k < lines.size) {
            val start = k
            var line = lines[k]
            while (line.endsWith("\\") && k + 1 < lines.size) {
                k++
                line = line.dropLast(1) + " " + lines[k]
            }
            k++
            val m = DIRECTIVE.matchEntire(line) ?: continue
            val directive = m.groupValues[1]
            val rest = m.groupValues[2]
            when (directive) {
                "if", "ifdef", "ifndef" -> stack.addLast(PpCondition(directive, rest))
                "elif", "elifdef", "elifndef" -> {
                    val previous = stack.removeLastOrNull()
                    stack.addLast(PpCondition(directive, rest + (previous?.let { " (after ${it.text})" } ?: "")))
                }
                "else" -> {
                    val previous = stack.removeLastOrNull()
                    stack.addLast(PpCondition("else", previous?.let { "#${it.directive} ${it.expression}" } ?: "?"))
                }
                "endif" -> stack.removeLastOrNull()
                "define" -> {
                    val d = DEFINE.matchEntire(rest) ?: continue
                    val raw = d.groupValues[2].trim()
                    if (raw.isEmpty() || raw.any { it.isWhitespace() || it == '(' }) continue
                    val name = d.groupValues[1]
                    val innermost = stack.lastOrNull()
                    val guarded = innermost != null && isGuardFor(innermost, name)
                    val conditions = if (guarded) stack.toList().dropLast(1) else stack.toList()
                    result += FlagDefine(name, DefineValue.parse(raw), raw, start, guarded, conditions)
                }
            }
        }
        return result
    }

    private fun isGuardFor(condition: PpCondition, name: String): Boolean = when (condition.directive) {
        "ifndef" -> condition.expression == name
        "if" -> condition.expression.replace(" ", "").let { it == "!defined($name)" || it == "!defined$name" }
        else -> false
    }

    private val ANY_DEFINE = Regex("""(?m)^\s*#\s*define\s+(NIHIL_\w+)""")

    /** Every `#define NIHIL_*` name, matched on the raw text like check-config-includes.py does. */
    fun allDefineNames(text: CharSequence): List<String> = ANY_DEFINE.findAll(text).map { it.groupValues[1] }.toList()

    fun features(text: CharSequence): List<FeatureDecl> {
        val code = CppText.stripComments(text)
        val starts = CppText.lineStarts(code)
        return FEATURE.findAll(code).map { m ->
            FeatureDecl(
                name = m.groupValues[1],
                description = CppText.unescape(m.groupValues[2]),
                value = m.groupValues[3].replace(Regex("""\s+"""), " "),
                line = CppText.lineAt(starts, m.range.first),
            )
        }.toList()
    }

    /** `NIHIL_IS_ENABLED(X)` calls outside comments; the macro's own definition (`x` parameter) is skipped. */
    fun uses(text: CharSequence): List<FlagUse> {
        val code = CppText.stripComments(text, blankStrings = true)
        return USE.findAll(code)
            .filter { m -> !isInsideDefineOfIsEnabled(code, m.range.first) }
            .map { FlagUse(it.groupValues[1], it.range.first, it.range.last + 1) }
            .toList()
    }

    private fun isInsideDefineOfIsEnabled(code: String, offset: Int): Boolean {
        val lineStart = code.lastIndexOf('\n', offset - 1) + 1
        return Regex("""^\s*#\s*define\s*$""").matches(code.substring(lineStart, offset))
    }

    /** True when a condition depends on the compiler rather than on the build: feature-test macros and friends. */
    fun isCompilerCondition(condition: PpCondition): Boolean =
        COMPILER_MARKERS.any { it in condition.expression }

    private val COMPILER_MARKERS = listOf(
        "__cpp_", "__has_include", "__has_cpp_attribute", "__has_builtin", "__has_feature",
        "_MSC_VER", "__clang__", "__GNUC__", "__cplusplus", "__INTELLISENSE__",
    )
}
