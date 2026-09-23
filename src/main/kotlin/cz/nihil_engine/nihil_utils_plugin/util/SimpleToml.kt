package cz.nihil_engine.nihil_utils_plugin.util

import java.io.File

/**
 * Minimal TOML reader shared by the plugin's config files.
 * Handles only what we need: tables, strings, booleans, and string arrays.
 * Values that are none of those (numbers, bare words) are returned as raw strings.
 * No external dependencies required.
 */
object SimpleToml {

    /** Table name -> (key -> value). Top-level keys live under "". Missing file -> empty map. */
    fun parseFile(file: File): Map<String, Map<String, Any>> =
        if (file.exists()) parse(file.readLines()) else emptyMap()

    fun parse(lines: List<String>): Map<String, Map<String, Any>> {
        val tables = mutableMapOf<String, MutableMap<String, Any>>()
        var currentTable = ""

        for (rawLine in lines) {
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

        return tables
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
}
