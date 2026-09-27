package cz.nihil_engine.nihil_utils_plugin.commit_checks

/**
 * Adds git trailers (`Key: value` lines in the message's last paragraph) so `git log --format=%(trailers)` can
 * read them. Git only treats the last paragraph as trailers, so new lines join an existing trailer paragraph
 * (Co-Authored-By, Signed-off-by) instead of starting a paragraph after it.
 */
object CommitTrailers {

    private val TRAILER = Regex("""^[A-Za-z0-9][A-Za-z0-9-]*:\s.*$""")

    /**
     * Removes every trailer whose key is in [ownedKeys], then appends [lines]. Re-running a check after a failed
     * commit therefore replaces its previous trailers rather than stacking them.
     */
    fun apply(message: String, ownedKeys: Set<String>, lines: List<String>): String {
        val kept = message.trimEnd().lines().filterNot { line -> ownedKeys.any { line.startsWith("$it:") } }
        val body = kept.joinToString("\n").trimEnd()
        if (lines.isEmpty()) return body
        if (body.isEmpty()) return lines.joinToString("\n")

        val lastParagraph = body.substringAfterLast("\n\n", body)
        val joinsTrailers = lastParagraph != body && lastParagraph.lines().all { TRAILER.matches(it) }
        val separator = if (joinsTrailers) "\n" else "\n\n"
        return body + separator + lines.joinToString("\n")
    }
}
