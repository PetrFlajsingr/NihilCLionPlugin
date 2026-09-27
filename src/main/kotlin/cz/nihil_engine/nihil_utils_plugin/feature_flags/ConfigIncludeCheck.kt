package cz.nihil_engine.nihil_utils_plugin.feature_flags

import cz.nihil_engine.nihil_utils_plugin.util.CppText

/**
 * Port of the engine's `.claude/scripts/check-config-includes.py`: a file that uses a library's config symbol
 * (a `#define NIHIL_*` or a `Feature` constant from its `config/config_*.hpp`) must include that library's
 * dispatcher `Nihil<Lib>/config.hpp` itself, not rely on another header dragging it in.
 *
 * Rules kept from the script:
 * - owners are libraries with a `config/` directory holding `config_*.hpp`, whose dispatcher is the single
 *   `Public/<Dir>/config.hpp` or `Private/Headers/<Dir>/config.hpp` (libraries with 0 or 2+ are skipped);
 *   the include to look for is `<Dir>/config.hpp`
 * - macros are every `#define NIHIL_*` in the config headers; features are `Feature Name{` there, used as `config::Name`
 * - comments and string literals are ignored when looking for uses; includes are matched on the raw text
 * - files inside a `config/` directory aren't checked
 */
class ConfigIncludeCheck(private val owners: Map<String, Owner>) {

    /** Symbols of one dispatcher include, e.g. `NihilJobs/config.hpp`. */
    data class Owner(val include: String, val macros: Set<String>, val features: Set<String>)

    /** [symbols] with the offset of their first use, for the warning markers. */
    data class Missing(val include: String, val symbols: Map<String, Int>)

    private val macroOwners: Map<String, List<String>> = owners.values
        .flatMap { o -> o.macros.map { it to o.include } }
        .groupBy({ it.first }, { it.second })
    private val featureOwners: Map<String, List<String>> = owners.values
        .flatMap { o -> o.features.map { it to o.include } }
        .groupBy({ it.first }, { it.second })

    val isEmpty: Boolean get() = owners.isEmpty()

    fun check(path: String, text: CharSequence): List<Missing> {
        if (path.substringBeforeLast('/').substringAfterLast('/') == "config") return emptyList()
        if (path.substringAfterLast('.').lowercase() !in SOURCE_SUFFIXES) return emptyList()
        val included = INCLUDE.findAll(text).map { it.groupValues[1] }.toSet()
        val code = CppText.stripComments(text, blankStrings = true)

        val needed = linkedMapOf<String, LinkedHashMap<String, Int>>()
        for (m in WORD.findAll(code)) {
            val word = m.value
            val includes = macroOwners[word] ?: continue
            for (include in includes) needed.getOrPut(include) { linkedMapOf() }.putIfAbsent(word, m.range.first)
        }
        for (m in FEATURE_USE.findAll(code)) {
            val includes = featureOwners[m.groupValues[1]] ?: continue
            for (include in includes) needed.getOrPut(include) { linkedMapOf() }.putIfAbsent("config::${m.groupValues[1]}", m.range.first)
        }
        return needed.filterKeys { it !in included }.map { (include, symbols) -> Missing(include, symbols) }
    }

    companion object {
        /** The script only checks these suffixes; `.inl` and friends are left alone there too. */
        val SOURCE_SUFFIXES = setOf("ixx", "cpp", "hpp", "h")

        private val INCLUDE = Regex("""(?m)^\s*#\s*include\s*[<"]([^>"]+/config\.hpp)[>"]""")
        private val WORD = Regex("""\b\w+\b""")
        private val FEATURE_USE = Regex("""\bconfig::(\w+)\b""")

        /**
         * [dispatchers]: library root -> directory names holding a dispatcher `config.hpp` under `Public/` or
         * `Private/Headers/`. Returns the owners plus warnings for roots that were skipped, as the script does.
         */
        fun owners(
            dispatchers: Map<String, List<String>>,
            macroNames: List<Located<String>>,
            features: List<Located<FeatureDecl>>,
        ): Pair<Map<String, Owner>, List<String>> {
            val warnings = mutableListOf<String>()
            val result = linkedMapOf<String, Owner>()
            val definesByRoot = macroNames.mapNotNull { d -> FeatureFlagModel.configHeader(d.path)?.let { it.first to d.item } }
                .groupBy({ it.first }, { it.second })
            val featuresByRoot = features.mapNotNull { f -> FeatureFlagModel.configHeader(f.path)?.let { it.first to f.item.name } }
                .groupBy({ it.first }, { it.second })
            for ((root, dirs) in dispatchers) {
                if (dirs.size != 1) {
                    warnings += "${root.substringAfterLast('/')} has ${dirs.size} config.hpp dispatchers; its symbols aren't checked"
                    continue
                }
                val include = "${dirs.single()}/config.hpp"
                result[include] = Owner(include, definesByRoot[root].orEmpty().toSet(), featuresByRoot[root].orEmpty().toSet())
            }
            return result to warnings
        }
    }
}
