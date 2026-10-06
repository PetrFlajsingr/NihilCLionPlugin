package cz.nihil_engine.nihil_utils_plugin.args

import cz.nihil_engine.nihil_utils_plugin.util.SimpleToml
import java.io.File

/**
 * Builds a [NihilArgsConfig] from nihil_args.toml. The TOML subset itself is read by [SimpleToml].
 */
object NihilArgsConfigParser {

    fun parse(file: File): NihilArgsConfig {
        if (!file.exists()) return NihilArgsConfig(emptyList())
        return buildConfig(SimpleToml.parseFile(file))
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
                        optional = argTable["optional"] == true,
                        enabledByDefault = argTable["enabled_by_default"] == true,
                        advanced = argTable["advanced"] == true,
                    )
                }

            val presetsPrefix = "$profileKey.presets."
            val presets = tables.keys
                .filter { it.startsWith(presetsPrefix) && '.' !in it.removePrefix(presetsPrefix) }
                .map { presetTableKey ->
                    val presetKey = presetTableKey.removePrefix(presetsPrefix)
                    val presetTable = tables[presetTableKey]!!
                    val values = presetTable.filterKeys { it != "label" }.mapValues { (_, value) ->
                        if (value is List<*>) value.joinToString("|") else value.toString()
                    }
                    // Optional args' on/off state lives in a [<profile>.presets.<preset>.enabled] sub-table.
                    val enabled = tables["$presetTableKey$ENABLED_SUFFIX"].orEmpty()
                        .entries.associate { (argKey, value) -> enabledValueKey(argKey) to value.toString() }
                    ArgPreset(
                        key = presetKey,
                        label = presetTable["label"] as? String ?: presetKey,
                        values = values + enabled,
                    )
                }

            TargetProfile(
                key = profileKey,
                label = label,
                filter = filter,
                args = args,
                presets = presets,
            )
        }

        return NihilArgsConfig(profiles)
    }
}