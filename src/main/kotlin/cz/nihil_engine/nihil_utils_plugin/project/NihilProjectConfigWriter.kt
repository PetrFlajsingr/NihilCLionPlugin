package cz.nihil_engine.nihil_utils_plugin.project

/**
 * Writes a [NihilProjectConfig] into `.idea/nihil_plugin.toml` by editing values in place: comments, key order
 * and keys the plugin doesn't know survive, since the file is also written by hand and committed.
 *
 * A key is only added when it differs from its default, so a new file stays short.
 */
object NihilProjectConfigWriter {

    const val HEADER = "# Nihil CLion Utils plugin: per-project opt-in. Without this file every plugin feature is off."

    fun update(existing: String?, config: NihilProjectConfig): String {
        val doc = TomlLines(existing?.lines()?.dropLastWhile { it.isBlank() } ?: listOf(HEADER))

        for (feature in NihilFeature.entries) {
            val enabled = config.isEnabled(feature)
            if (enabled || doc.has("features", feature.key)) doc.set("features", feature.key, enabled.toString())
        }

        val bt = config.buildTargets
        val btDefaults = BuildTargetsConfig()
        doc.setUnlessDefault("build_targets", "cmake_variable", quote(bt.cmakeVariable), bt.cmakeVariable == btDefaults.cmakeVariable)
        doc.setUnlessDefault("build_targets", "targets", array(bt.targets), bt.targets == btDefaults.targets)
        doc.setUnlessDefault("build_targets", "default_target", quote(bt.defaultTarget), bt.defaultTarget == btDefaults.defaultTarget)
        doc.setUnlessDefault("build_targets", "profile_name", quote(bt.profileName), bt.profileName == btDefaults.profileName)

        val ct = config.commitTests
        doc.setUnlessDefault("commit_tests", "profile", quote(ct.profile), ct.profile == CommitTestsConfig().profile)
        for (library in doc.keys("commit_tests.targets") - ct.targets.keys) doc.remove("commit_tests.targets", library)
        for ((library, targets) in ct.targets) doc.set("commit_tests.targets", library, array(targets))

        val cv = config.cvars
        val cvDefaults = CVarsConfig()
        doc.setUnlessDefault("cvars", "port", cv.port.toString(), cv.port == cvDefaults.port)
        doc.setUnlessDefault("cvars", "poll_interval_ms", cv.pollIntervalMs.toString(), cv.pollIntervalMs == cvDefaults.pollIntervalMs)

        return doc.lines.joinToString("\n") + "\n"
    }

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun array(values: List<String>) = values.joinToString(", ", "[", "]") { quote(it) }

    /** The subset of TOML that SimpleToml reads, edited line by line. */
    private class TomlLines(initial: List<String>) {
        val lines = initial.toMutableList()

        private fun headerOf(line: String): String? {
            val code = codePart(line).trim()
            return if (code.startsWith("[") && code.endsWith("]")) code.drop(1).dropLast(1).trim() else null
        }

        private fun keyOf(line: String): String? {
            val code = codePart(line)
            val eq = code.indexOf('=')
            return if (eq < 0 || headerOf(line) != null) null else code.substring(0, eq).trim().takeIf { it.isNotEmpty() }
        }

        /** Line range of [table]'s keys (after its header up to the next header), or null when it doesn't exist. */
        private fun range(table: String): IntRange? {
            val header = lines.indexOfFirst { headerOf(it) == table }
            if (header < 0) return null
            val next = (header + 1 until lines.size).firstOrNull { headerOf(lines[it]) != null } ?: lines.size
            return header + 1 until next
        }

        private fun indexOf(table: String, key: String): Int? = range(table)?.firstOrNull { keyOf(lines[it]) == key }

        fun has(table: String, key: String) = indexOf(table, key) != null

        fun keys(table: String): Set<String> = range(table)?.mapNotNull { keyOf(lines[it]) }?.toSet().orEmpty()

        fun setUnlessDefault(table: String, key: String, literal: String, isDefault: Boolean) {
            if (!isDefault || has(table, key)) set(table, key, literal)
        }

        fun set(table: String, key: String, literal: String) {
            indexOf(table, key)?.let { i ->
                val line = lines[i]
                val code = codePart(line)
                val comment = line.substring(code.length)
                val eq = code.indexOf('=')
                val padding = code.substring(eq + 1).let { v -> v.substring(v.trimEnd().length) }
                lines[i] = code.substring(0, eq + 1) + " " + literal + (if (comment.isNotEmpty()) padding.ifEmpty { " " } + comment else "")
                return
            }
            val r = range(table)
            if (r == null) {
                if (lines.isNotEmpty() && lines.last().isNotBlank()) lines += ""
                lines += "[$table]"
                lines += "$key = $literal"
                return
            }
            // After the table's last key: blank lines and comments that follow it introduce the next table
            val insertAt = r.lastOrNull { keyOf(lines[it]) != null }?.plus(1) ?: r.first
            lines.add(insertAt, "$key = $literal")
        }

        fun remove(table: String, key: String) {
            indexOf(table, key)?.let { lines.removeAt(it) }
        }

        /** The line without its `#` comment; a `#` inside a string doesn't start one. */
        private fun codePart(line: String): String {
            var inString = false
            var i = 0
            while (i < line.length) {
                val ch = line[i]
                if (inString) {
                    if (ch == '\\') { i += 2; continue }
                    if (ch == '"') inString = false
                } else if (ch == '"') {
                    inString = true
                } else if (ch == '#') {
                    return line.substring(0, i)
                }
                i++
            }
            return line
        }
    }
}
