package cz.nihil_engine.nihil_utils_plugin.util

/** Text helpers for scanning C++ sources without a syntax tree (CLion Nova has none on the plugin side). */
object CppText {

    /**
     * [text] with `//` and `/* */` comments replaced by spaces, newlines kept, so offsets and line numbers still
     * match the original. With [blankStrings] string and character literal contents are blanked too.
     */
    fun stripComments(text: CharSequence, blankStrings: Boolean = false): String {
        val out = StringBuilder(text.length)
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            val next = if (i + 1 < n) text[i + 1] else '\u0000'
            when {
                c == '/' && next == '/' -> {
                    while (i < n && text[i] != '\n') { out.append(' '); i++ }
                }
                c == '/' && next == '*' -> {
                    out.append("  ")
                    i += 2
                    while (i < n && !(text[i] == '*' && i + 1 < n && text[i + 1] == '/')) {
                        out.append(if (text[i] == '\n') '\n' else ' ')
                        i++
                    }
                    if (i < n) { out.append("  "); i += 2 }
                }
                c == 'R' && next == '"' && (i == 0 || !isIdent(text[i - 1])) -> i = copyRawString(text, i, out, blankStrings)
                c == '"' || (c == '\'' && (i == 0 || !text[i - 1].isLetterOrDigit())) -> {
                    // A ' after a digit is a digit separator (1'000), not a character literal.
                    out.append(c)
                    i++
                    while (i < n && text[i] != c && text[i] != '\n') {
                        if (text[i] == '\\' && i + 1 < n) {
                            out.append(if (blankStrings) "  " else text.subSequence(i, i + 2))
                            i += 2
                            continue
                        }
                        out.append(if (blankStrings) ' ' else text[i])
                        i++
                    }
                    if (i < n && text[i] == c) { out.append(c); i++ }
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    private fun copyRawString(text: CharSequence, start: Int, out: StringBuilder, blank: Boolean): Int {
        val open = text.indexOf('(', start + 2)
        if (open < 0 || open - start > 18) { out.append(text[start]); return start + 1 }
        val delimiter = text.subSequence(start + 2, open).toString()
        val end = text.indexOf(")$delimiter\"", open)
        val stop = if (end < 0) text.length else end + delimiter.length + 2
        for (k in start until stop) {
            val ch = text[k]
            out.append(if (blank && k > open && k < (if (end < 0) stop else end) && ch != '\n') ' ' else ch)
        }
        return stop
    }

    private fun isIdent(c: Char) = c.isLetterOrDigit() || c == '_'

    /** 0-based line of [offset]. */
    fun lineOf(text: CharSequence, offset: Int): Int {
        var line = 0
        for (k in 0 until minOf(offset, text.length)) if (text[k] == '\n') line++
        return line
    }

    /** Start offsets of each line, for repeated offset -> line lookups. */
    fun lineStarts(text: CharSequence): IntArray {
        val starts = ArrayList<Int>()
        starts += 0
        for (k in text.indices) if (text[k] == '\n') starts += k + 1
        return starts.toIntArray()
    }

    fun lineAt(lineStarts: IntArray, offset: Int): Int {
        val found = lineStarts.binarySearch(offset)
        return if (found >= 0) found else -found - 2
    }

    /** Decodes the simple escapes a cvar name or help string can contain. */
    fun unescape(literalBody: String): String {
        if ('\\' !in literalBody) return literalBody
        val sb = StringBuilder()
        var k = 0
        while (k < literalBody.length) {
            val ch = literalBody[k]
            if (ch == '\\' && k + 1 < literalBody.length) {
                sb.append(
                    when (val e = literalBody[k + 1]) {
                        'n' -> '\n'
                        't' -> '\t'
                        else -> e
                    }
                )
                k += 2
            } else {
                sb.append(ch)
                k++
            }
        }
        return sb.toString()
    }
}
