package cz.nihil_engine.nihil_utils_plugin.scaffold

/** Adds an `add_subdirectory(...)` line to a CMakeLists.txt, next to the ones like it. */
object CMakeRegistration {

    data class Result(
        val text: String,
        /** False when [section] wasn't found and the line went to the end of the file. */
        val placed: Boolean,
    )

    /**
     * Inserts [line] after the last line of the first run of `add_subdirectory(...)` lines that follows [section] (a
     * line whose trimmed text equals it; the start of the file when null), with that run's indentation. Returns null
     * when [line] is already there.
     */
    fun insert(text: String, line: String, section: String?): Result? {
        val separator = if ("\r\n" in text) "\r\n" else "\n"
        val trailing = text.endsWith("\n")
        val lines = text.lines().let { if (trailing) it.dropLast(1) else it }.toMutableList()
        if (lines.any { it.trim() == line.trim() }) return null

        val sectionIndex = if (section == null) -1 else lines.indexOfFirst { it.trim() == section.trim() }
        if (section != null && sectionIndex < 0) {
            lines += line
            return Result(lines.joinToString(separator) + separator, placed = false)
        }

        val first = (sectionIndex + 1 until lines.size).firstOrNull { isAddSubdirectory(lines[it]) }
        val insertAt: Int
        val indent: String
        if (first == null) {
            insertAt = sectionIndex + 1
            indent = ""
        } else {
            var last = first
            while (last + 1 < lines.size && isAddSubdirectory(lines[last + 1])) last++
            insertAt = last + 1
            indent = lines[last].takeWhile { it == ' ' || it == '\t' }
        }
        lines.add(insertAt, indent + line.trim())
        return Result(lines.joinToString(separator) + if (trailing) separator else "", placed = true)
    }

    private fun isAddSubdirectory(line: String) = line.trimStart().startsWith("add_subdirectory(")
}
