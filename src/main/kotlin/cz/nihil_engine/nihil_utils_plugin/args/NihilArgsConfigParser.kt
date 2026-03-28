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
            val line = rawLine.substringBefore('#').trim()
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

    private fun parseValue(raw: String): Any = when {
        raw == "true" -> true
        raw == "false" -> false
        raw.startsWith('[') && raw.endsWith(']') -> {
            raw.drop(1).dropLast(1)
                .split(',')
                .map { it.trim().removeSurrounding("\"") }
                .filter { it.isNotEmpty() }
        }
        raw.startsWith('"') && raw.endsWith('"') -> raw.drop(1).dropLast(1)
        else -> raw
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
                        "derived" -> ArgType.DERIVED
                        else -> ArgType.BOOL
                    }
                    ArgDefinition(
                        key = argKey,
                        label = argTable["label"] as? String ?: argKey,
                        type = type,
                        flag = argTable["flag"] as? String ?: "--$argKey",
                        default = argTable["default"]?.toString() ?: "",
                        options = (argTable["options"] as? List<*>)?.map { it.toString() } ?: emptyList(),
                        valueTemplate = argTable["value"] as? String ?: "",
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