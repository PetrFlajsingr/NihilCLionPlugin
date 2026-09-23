package cz.nihil_engine.nihil_utils_plugin.project

import cz.nihil_engine.nihil_utils_plugin.util.SimpleToml
import java.io.File

/**
 * Reads `.idea/nihil_plugin.toml`:
 *
 * ```
 * [features]
 * build_target_selector = true
 * tool_buttons = true
 * args_popup = true
 * assert_menu = true
 *
 * [build_targets]
 * cmake_variable = "NIHIL_BUILD_TARGET"
 * targets = ["Game", "Editor", "Tools"]
 * default_target = "Game"
 * profile_name = "{variant} {target}"
 * ```
 */
object NihilProjectConfigParser {

    fun parse(file: File): NihilProjectConfig {
        if (!file.exists()) return NihilProjectConfig.ABSENT
        return fromTables(SimpleToml.parseFile(file))
    }

    fun fromTables(tables: Map<String, Map<String, Any>>): NihilProjectConfig {
        val problems = mutableListOf<String>()

        val featureTable = tables["features"].orEmpty()
        val features = NihilFeature.entries.filter { featureTable[it.key] == true }.toSet()
        val knownKeys = NihilFeature.entries.map { it.key }.toSet()
        for ((key, value) in featureTable) {
            if (key !in knownKeys) problems += "[features] $key: unknown feature"
            else if (value !is Boolean) problems += "[features] $key: expected true or false"
        }

        val defaults = BuildTargetsConfig()
        val bt = tables["build_targets"].orEmpty()
        val cmakeVariable = (bt["cmake_variable"] as? String)?.takeIf { it.isNotBlank() } ?: defaults.cmakeVariable
        val targets = ((bt["targets"] as? List<*>)?.map { it.toString() }?.filter { it.isNotBlank() })
            ?.takeIf { it.isNotEmpty() } ?: defaults.targets
        var defaultTarget = (bt["default_target"] as? String) ?: defaults.defaultTarget
        if (targets.none { it.equals(defaultTarget, ignoreCase = true) }) {
            problems += "[build_targets] default_target \"$defaultTarget\" is not in targets; using \"${targets.first()}\""
            defaultTarget = targets.first()
        } else {
            defaultTarget = targets.first { it.equals(defaultTarget, ignoreCase = true) }
        }
        var profileName = (bt["profile_name"] as? String) ?: defaults.profileName
        if ("{variant}" !in profileName || "{target}" !in profileName) {
            problems += "[build_targets] profile_name must contain {variant} and {target}; using \"${defaults.profileName}\""
            profileName = defaults.profileName
        }

        return NihilProjectConfig(
            present = true,
            features = features,
            buildTargets = BuildTargetsConfig(cmakeVariable, targets, defaultTarget, profileName),
            problems = problems,
        )
    }
}
