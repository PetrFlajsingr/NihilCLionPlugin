package cz.nihil_engine.nihil_utils_plugin.scaffold

/**
 * The template language of `.idea/nihil_templates`: `{{name}}` placeholders and `{{#if flag}}` / `{{#unless flag}}` /
 * `{{/if}}` / `{{/unless}}` blocks on lines of their own. Double braces keep clear of CMake's `${...}`.
 *
 * A placeholder alone on its line takes the line's indentation for every line of a multi-line value, and removes the
 * line when the value is empty: that is how dependency lists are written.
 */
object TemplateRenderer {

    private val PLACEHOLDER = Regex("""\{\{\s*([A-Za-z_][\w]*)\s*}}""")
    private val OPEN = Regex("""^\{\{#(if|unless)\s+([A-Za-z_][\w]*)\s*}}$""")
    private val CLOSE = Regex("""^\{\{/(if|unless)\s*}}$""")

    class TemplateException(message: String) : Exception(message)

    /** Renders [template]; throws [TemplateException] for an unknown placeholder or unbalanced blocks. */
    fun render(template: String, vars: Map<String, String>, flags: Set<String>): String {
        val separator = if ("\r\n" in template) "\r\n" else "\n"
        val out = mutableListOf<String>()
        // For each open block: whether its lines are emitted.
        val active = ArrayDeque<Boolean>()

        for ((index, line) in template.lines().withIndex()) {
            val trimmed = line.trim()
            OPEN.matchEntire(trimmed)?.let { m ->
                val on = m.groupValues[2] in flags
                active.addLast((active.lastOrNull() ?: true) && (if (m.groupValues[1] == "if") on else !on))
                continue
            }
            if (CLOSE.matches(trimmed)) {
                if (active.isEmpty()) throw TemplateException("line ${index + 1}: {{/…}} without an opening block")
                active.removeLast()
                continue
            }
            if (active.lastOrNull() == false) continue

            val alone = PLACEHOLDER.matchEntire(trimmed)
            if (alone != null) {
                val value = lookup(vars, alone.groupValues[1], index)
                if (value.isEmpty()) continue
                val indent = line.takeWhile { it == ' ' || it == '\t' }
                value.lines().forEach { out += indent + it }
            } else {
                out += PLACEHOLDER.replace(line) { lookup(vars, it.groupValues[1], index) }
            }
        }
        if (active.isNotEmpty()) throw TemplateException("${active.size} block(s) not closed")
        return out.joinToString(separator)
    }

    /** Placeholders in a file path; no blocks. */
    fun renderPath(path: String, vars: Map<String, String>): String =
        PLACEHOLDER.replace(path) { lookup(vars, it.groupValues[1], -1) }

    private fun lookup(vars: Map<String, String>, name: String, index: Int): String =
        vars[name] ?: throw TemplateException(
            (if (index >= 0) "line ${index + 1}: " else "") + "unknown placeholder {{$name}}; known: ${vars.keys.sorted().joinToString()}",
        )
}
