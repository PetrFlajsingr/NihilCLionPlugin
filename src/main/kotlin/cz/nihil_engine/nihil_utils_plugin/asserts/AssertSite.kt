package cz.nihil_engine.nihil_utils_plugin.asserts

data class AssertSite(val macro: String, val kind: AssertKind, val id: Long, val line: Int) {
    val idText: String get() = formatId(id)

    companion object {
        fun formatId(id: Long): String = "0x" + java.lang.Long.toHexString(id).uppercase()

        fun parseId(hex: String): Long? = hex.removePrefix("0x").removePrefix("0X").toULongOrNull(16)?.toLong()
        fun findAt(text: CharSequence, line: Int, macros: AssertMacros, maxLinesBack: Int = 12): AssertSite? {
            val lineStarts = lineStartOffsets(text)
            if (line !in lineStarts.indices) return null
            val windowStart = lineStarts[(line - maxLinesBack).coerceAtLeast(0)]
            val windowEnd = if (line + 2 < lineStarts.size) lineStarts[line + 2] else text.length
            val sites = find(text.subSequence(windowStart, windowEnd), macros) { lineOf(lineStarts, windowStart + it) }
            return sites.lastOrNull { it.line <= line } ?: sites.firstOrNull { it.line > line }
        }

        fun findAll(text: CharSequence, macros: AssertMacros): List<AssertSite> {
            val lineStarts = lineStartOffsets(text)
            return find(text, macros) { lineOf(lineStarts, it) }
        }

        private fun find(text: CharSequence, macros: AssertMacros, lineAt: (Int) -> Int): List<AssertSite> =
            macros.invocation.findAll(text).mapNotNull { m ->
                val macro = m.groupValues[1]
                val id = parseId(m.groupValues[2]) ?: return@mapNotNull null
                AssertSite(macro, AssertKind.of(macro), id, lineAt(m.range.first))
            }.toList()

        private fun lineStartOffsets(text: CharSequence): List<Int> = buildList {
            add(0)
            text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) }
        }

        private fun lineOf(lineStarts: List<Int>, offset: Int): Int {
            val idx = lineStarts.binarySearch(offset)
            return if (idx >= 0) idx else -idx - 2
        }
    }
}
