package cz.nihil_engine.nihil_utils_plugin.args

import java.io.File

/**
 * Minimal TOML parser for the nihil_args.toml schema.
 * Handles only what we need: tables, strings, booleans, and string arrays.
 * No external dependencies required.
 */
object NihilArgsConfigParser {

    fun parse(file: File): NihilArgsConfig {
        if (!file.exists()) return NihilArgsConfig(emptyList())

        val tables = mutableMapOf<String, MutableMap<String, Any>>()
        var currentTable = ""

        for (rawLine in file.readLines()) {
            val line = stripLineComment(rawLine).trim()
            if (line.isEmpty()) continue

            if (line.startsWith('[') && line.endsWith(']')) {
                currentTable = line.drop(1).dropLast(1).trim()
                tables.getOrPut(currentTable) { mutableMapOf() }
                continue
            }

            val eqIndex = line.indexOf('=')
            if (eqIndex < 0) continue

            val key = line.substring(0, eqIndex).trim()
            val value = line.substring(eqIndex + 1).trim()
            tables.getOrPut(currentTable) { mutableMapOf() }[key] = parseValue(value)
        }

        return buildConfig(tables)
    }

    private fun stripLineComment(line: String): String {
        var inString = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            if (inString) {
                if (ch == '\\') { i += 2; continue }
                if (ch == '"') inString = false
            } else {
                if (ch == '"') inString = true
                else if (ch == '#') return line.substring(0, i)
            }
            i++
        }
        return line
    }

    private fun parseValue(raw: String): Any = when {
        raw == "true" -> true
        raw == "false" -> false
        raw.startsWith('[') && raw.endsWith(']') -> splitArrayElements(raw.drop(1).dropLast(1))
        raw.startsWith('"') && raw.endsWith('"') -> unescapeTomlString(raw.drop(1).dropLast(1))
        else -> raw
    }

    private fun splitArrayElements(inner: String): List<String> {
        val results = mutableListOf<String>()
        val current = StringBuilder()
        var inString = false
        var i = 0
        while (i < inner.length) {
            val ch = inner[i]
            if (inString) {
                when {
                    ch == '\\' && i + 1 < inner.length -> {
                        current.append(unescapeChar(inner[i + 1]))
                        i += 2; continue
                    }
                    ch == '"' -> inString = false
                    else -> current.append(ch)
                }
            } else {
                when (ch) {
                    '"' -> inString = true
                    ',' -> {
                        val elem = current.toString().trim()
                        if (elem.isNotEmpty()) results.add(elem)
                        current.clear()
                    }
                    else -> if (!ch.isWhitespace()) current.append(ch)
                }
            }
            i++
        }
        val last = current.toString().trim()
        if (last.isNotEmpty()) results.add(last)
        return results
    }

    private fun unescapeTomlString(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length) {
                sb.append(unescapeChar(s[i + 1]))
                i += 2
            } else {
                sb.append(s[i++])
            }
        }
        return sb.toString()
    }

    private fun unescapeChar(ch: Char): Char = when (ch) {
        '"'  -> '"'
        '\\' -> '\\'
        'n'  -> '\n'
        'r'  -> '\r'
        't'  -> '\t'
        else -> ch
    }

    private fun buildConfig(tables: Map<String, Map<String, Any>>): NihilArgsConfig {
        val profileKeys = tables.keys
            .filter { !it.contains('.') }
            .filter { tables[it]?.containsKey("label") == true && tables[it]?.containsKey("filter") == true }

        val profiles = profileKeys.map { profileKey ->
            val profileTable = tables[profileKey]!!
            val label = profileTable["label"] as? String ?: profileKey
            val filter = (profileTable["filter"] as? String ?: ".*").toRegex()

            val argsPrefix = "$profileKey.args."
            val args = tables.keys
                .filter { it.startsWith(argsPrefix) && it.removePrefix(argsPrefix).count { c -> c == '.' } == 0 }
                .map { argTableKey ->
                    val argKey = argTableKey.removePrefix(argsPrefix)
                    val argTable = tables[argTableKey]!!
                    val type = when (argTable["type"] as? String) {
                        "bool" -> ArgType.BOOL
                        "select" -> ArgType.SELECT
                        "text" -> ArgType.TEXT
                        "path" -> ArgType.PATH
                        "int" -> ArgType.INT
                        "multi" -> ArgType.MULTI
                        "derived" -> ArgType.DERIVED
                        else -> ArgType.BOOL
                    }
                    val pathKind = when (argTable["path_kind"] as? String) {
                        "directory" -> PathKind.DIRECTORY
                        else -> PathKind.FILE
                    }
                    val pathDirection = when (argTable["path_direction"] as? String) {
                        "output" -> PathDirection.OUTPUT
                        else -> PathDirection.INPUT
                    }
                    ArgDefinition(
                        key = argKey,
                        label = argTable["label"] as? String ?: argKey,
                        type = type,
                        flag = argTable["flag"] as? String ?: "--$argKey",
                        default = argTable["default"]?.toString() ?: "",
                        options = (argTable["options"] as? List<*>)?.map { it.toString() } ?: emptyList(),
                        valueTemplate = argTable["value"] as? String ?: "",
                        pathKind = pathKind,
                        pathDirection = pathDirection,
                        min = argTable["min"]?.toString()?.toIntOrNull(),
                        max = argTable["max"]?.toString()?.toIntOrNull(),
                        separator = argTable["separator"] as? String ?: ",",
                    )
                }

            TargetProfile(
                key = profileKey,
                label = label,
                filter = filter,
                args = args,
            )
        }

        return NihilArgsConfig(profiles)
    }
}