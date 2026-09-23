package cz.nihil_engine.nihil_utils_plugin.asserts

object IgnoreListFormat {

    fun parse(text: String): Set<Long> = text.lineSequence().mapNotNull(::idOf).toSet()

    fun withAdded(text: String, id: Long): String? {
        if (id in parse(text)) return null
        val separator = lineSeparator(text)
        val prefix = if (text.isNotEmpty() && !text.endsWith('\n')) separator else ""
        return text + prefix + AssertSite.formatId(id) + separator
    }

    fun withRemoved(text: String, id: Long): String {
        val separator = lineSeparator(text)
        val trailing = text.endsWith('\n')
        val kept = text.lines().let { if (trailing) it.dropLast(1) else it }.filter { idOf(it) != id }
        return kept.joinToString(separator) + if (trailing && kept.isNotEmpty()) separator else ""
    }

    private fun idOf(line: String): Long? {
        val t = line.trim()
        return if (t.startsWith("0x", ignoreCase = true)) AssertSite.parseId(t) else null
    }

    private fun lineSeparator(text: String) = if ("\r\n" in text) "\r\n" else "\n"
}
