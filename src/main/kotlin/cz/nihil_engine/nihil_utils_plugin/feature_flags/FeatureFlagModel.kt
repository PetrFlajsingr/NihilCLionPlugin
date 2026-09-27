package cz.nihil_engine.nihil_utils_plugin.feature_flags

/** A place to jump to: a system-independent absolute path and a 0-based line. */
data class SourceLink(val path: String, val line: Int) {
    val fileName: String get() = path.substringAfterLast('/')
}

enum class CellState(val symbol: String, val description: String) {
    ENABLED("✓", "enabled"),
    DISABLED("✗", "disabled"),
    /** The plugin can't tell: no profile, an unreadable condition, a value that isn't NIHIL_ENABLED/DISABLED. */
    UNKNOWN("?", "unknown"),
    /** The profiles of this build type disagree, or the flag is defined differently per file. */
    MIXED("~", "differs"),
    /** Not defined at all in this build type: NIHIL_IS_ENABLED on it doesn't compile. */
    UNDEFINED("–", "not defined"),
    COMPILER("c", "compiler-dependent"),
}

/** One line of "where does this value come from". */
data class CellReason(val text: String, val link: SourceLink? = null)

data class FlagCell(
    val state: CellState,
    val reasons: List<CellReason> = emptyList(),
    /** An `#ifndef`-guarded default: a build can define the flag before the header to override it. */
    val overridable: Boolean = false,
) {
    val symbol: String get() = state.symbol + if (overridable) "*" else ""
}

enum class FlagKind(val label: String) {
    CONFIG_HEADER("library config header"),
    CMAKE("CMake option"),
    COMPILER("compiler-dependent"),
    SOURCE("defined in a source file"),
    CONDITIONAL("conditionally defined"),
    NOT_FOUND("no definition found"),
}

data class FlagInfo(
    val name: String,
    val kind: FlagKind,
    /** Library root (`system/Jobs`), `cmake/NihilFlags.cmake` or the defining file's name. */
    val owner: String,
    val cells: Map<BuildType, FlagCell>,
    val definitions: List<SourceLink>,
    /** For flags defined in source files: a file's own definition wins inside that file. */
    val perFile: Map<String, Map<BuildType, FlagCell>> = emptyMap(),
    val note: String? = null,
) {
    fun cellsFor(path: String?): Map<BuildType, FlagCell> = path?.let { perFile[it] } ?: cells
}

data class FeatureConstant(
    val name: String,
    val owner: String,
    val description: String,
    val values: Map<BuildType, String>,
    val links: Map<BuildType, SourceLink>,
)

data class ProfileFlagValue(val state: CellState, val reason: String, val link: SourceLink?)

/** A CMake profile as the flags see it. */
data class ResolvedProfile(
    val name: String,
    val enabled: Boolean,
    val buildType: BuildType?,
    val buildTypeReason: String,
    /** CMake option values that matter to some flag, with where they came from. */
    val variables: List<VariableValue>,
    val flags: Map<String, ProfileFlagValue>,
)

/** A CMake profile's raw inputs, decoupled from CMakeSettings so the model can be tested. */
data class ProfileInputs(
    val name: String,
    val enabled: Boolean,
    /** CLion's build type (CMAKE_BUILD_TYPE) of the profile. */
    val buildType: String?,
    val generationDefines: Map<String, String>,
    /** CMakeCache.txt of the profile's build directory, null when it hasn't been generated. */
    val cache: Map<String, String>?,
    val cachePath: String? = null,
)

/** A located scan result: [path] is system-independent and absolute. */
data class Located<T>(val path: String, val item: T)

class FeatureFlagModel(
    val flags: Map<String, FlagInfo>,
    val features: List<FeatureConstant>,
    val profiles: List<ResolvedProfile>,
    val cmakeOptions: List<CMakeOption>,
    val problems: List<String>,
) {
    fun profile(name: String?): ResolvedProfile? = name?.let { n -> profiles.firstOrNull { it.name == n } }

    /** Paths whose change invalidates the model (besides config headers and NihilFlags.cmake). */
    val sourceDefinitionPaths: Set<String> =
        flags.values.filter { it.kind != FlagKind.CONFIG_HEADER && it.kind != FlagKind.CMAKE }.flatMap { f -> f.definitions.map { it.path } }.toSet()

    companion object {
        val EMPTY = FeatureFlagModel(emptyMap(), emptyList(), emptyList(), emptyList(), emptyList())

        private val CONFIG_HEADER = Regex("""^(.*)/config/(config_(?:debug|development|profiling|release|test)\.hpp)$""")

        /** `(library root, build type)` for `<root>/config/config_<type>.hpp`. */
        fun configHeader(path: String): Pair<String, BuildType>? {
            val m = CONFIG_HEADER.matchEntire(path) ?: return null
            return m.groupValues[1] to (BuildType.fromConfigFileName(m.groupValues[2]) ?: return null)
        }

        /** `system/Jobs` for `F:/Engine/src/system/Jobs` given source root `F:/Engine/src`. */
        fun libraryLabel(root: String, sourceRoot: String?): String =
            if (sourceRoot != null && root.startsWith("$sourceRoot/")) root.removePrefix("$sourceRoot/") else root.substringAfterLast('/')

        fun build(
            defines: List<Located<FlagDefine>>,
            features: List<Located<FeatureDecl>>,
            cmake: CMakeFlagScript?,
            cmakePath: String?,
            profiles: List<ProfileInputs>,
            sourceRoot: String?,
        ): FeatureFlagModel {
            val problems = mutableListOf<String>()
            val flags = linkedMapOf<String, FlagInfo>()

            val resolved = profiles.map { resolveProfile(it, cmake, cmakePath) }

            // 1. Per-library config headers.
            val headerDefines = defines.mapNotNull { d -> configHeader(d.path)?.let { (root, bt) -> Triple(root, bt, d) } }
            for ((name, group) in headerDefines.groupBy { it.third.item.name }) {
                val roots = group.map { it.first }.distinct()
                if (roots.size > 1) {
                    problems += "$name is defined by the config headers of ${roots.joinToString { libraryLabel(it, sourceRoot) }}; showing ${libraryLabel(roots.first(), sourceRoot)}"
                }
                val root = roots.first()
                val mine = group.filter { it.first == root }
                val cells = BuildType.entries.associateWith { bt ->
                    val here = mine.filter { it.second == bt }.map { it.third }
                    if (here.isEmpty()) {
                        FlagCell(CellState.UNDEFINED, listOf(CellReason("not defined in ${bt.configFileName}", SourceLink("$root/config/${bt.configFileName}", 0))))
                    } else {
                        cellFromDefines(here)
                    }
                }
                flags[name] = FlagInfo(
                    name, FlagKind.CONFIG_HEADER, libraryLabel(root, sourceRoot), cells,
                    mine.map { SourceLink(it.third.path, it.third.item.line) },
                )
            }

            // 2. CMake-driven flags: per build type, from the profiles that build it.
            if (cmake != null && cmakePath != null) {
                for (name in cmake.flagNames) {
                    if (name in flags) {
                        problems += "$name is set both by a config header and by ${cmakePath.substringAfterLast('/')}"
                        continue
                    }
                    val defs = cmake.definitionsOf(name)
                    val cells = BuildType.entries.associateWith { bt -> cmakeCell(name, bt, resolved) }
                    flags[name] = FlagInfo(
                        name, FlagKind.CMAKE, "cmake/${cmakePath.substringAfterLast('/')}", cells,
                        defs.map { SourceLink(cmakePath, it.line) },
                    )
                }
            }

            // 3. Everything else: defines in plain headers and sources.
            val others = defines.filter { configHeader(it.path) == null && it.item.name !in flags }
            for ((name, group) in others.groupBy { it.item.name }) {
                if (group.all { it.item.value == DefineValue.OTHER }) continue
                flags[name] = sourceFlag(name, group)
            }

            val featureConstants = features.mapNotNull { f -> configHeader(f.path)?.let { (root, bt) -> Triple(root, bt, f) } }
                .groupBy { it.first to it.third.item.name }
                .map { (key, group) ->
                    val first = group.first().third.item
                    FeatureConstant(
                        name = key.second,
                        owner = libraryLabel(key.first, sourceRoot),
                        description = first.description,
                        values = group.associate { it.second to it.third.item.value },
                        links = group.associate { it.second to SourceLink(it.third.path, it.third.item.line) },
                    )
                }
                .sortedWith(compareBy({ it.owner }, { it.name }))

            return FeatureFlagModel(flags, featureConstants, resolved, cmake?.options?.values?.toList().orEmpty(), problems)
        }

        private fun cellFromDefines(defines: List<Located<FlagDefine>>): FlagCell {
            val cells = defines.map { cellFromDefine(it) }
            if (cells.size == 1) return cells.single()
            // Several defines of one flag in one header: normally #if/#else branches.
            val states = cells.map { it.state }.distinct()
            val state = if (states.size == 1) states.single() else if (cells.any { it.state == CellState.COMPILER }) CellState.COMPILER else CellState.UNKNOWN
            return FlagCell(state, cells.flatMap { it.reasons }, cells.any { it.overridable })
        }

        private fun cellFromDefine(located: Located<FlagDefine>): FlagCell {
            val d = located.item
            val link = SourceLink(located.path, d.line)
            val where = located.path.substringAfterLast('/') + ":" + (d.line + 1)
            val valueState = when (d.value) {
                DefineValue.ENABLED -> CellState.ENABLED
                DefineValue.DISABLED -> CellState.DISABLED
                DefineValue.OTHER -> CellState.UNKNOWN
            }
            val conditionText = d.conditions.joinToString(", ") { it.text }
            val state = when {
                d.conditions.isEmpty() -> valueState
                d.conditions.any(CppFlagScanner::isCompilerCondition) -> CellState.COMPILER
                else -> CellState.UNKNOWN
            }
            val text = buildString {
                append(if (d.value == DefineValue.OTHER) "defined as ${d.rawValue}" else valueState.description)
                if (d.guarded) append(" by default, unless defined before the header (#ifndef)")
                if (conditionText.isNotEmpty()) append(" under $conditionText")
                append(" — $where")
            }
            return FlagCell(state, listOf(CellReason(text, link)), overridable = d.guarded)
        }

        private fun sourceFlag(name: String, group: List<Located<FlagDefine>>): FlagInfo {
            val links = group.map { SourceLink(it.path, it.item.line) }
            val files = group.groupBy { it.path }
            val perFile = files.mapValues { (_, defs) ->
                val cell = cellFromDefines(defs)
                BuildType.entries.associateWith { cell }
            }
            val fileCells = perFile.values.map { it.getValue(BuildType.DEBUG) }
            val compiler = fileCells.any { it.state == CellState.COMPILER }
            val conditional = group.any { it.item.conditions.isNotEmpty() }
            val kind = when {
                compiler -> FlagKind.COMPILER
                conditional -> FlagKind.CONDITIONAL
                else -> FlagKind.SOURCE
            }
            val merged = if (fileCells.map { it.state to it.overridable }.distinct().size == 1) {
                fileCells.first().copy(reasons = fileCells.flatMap { it.reasons })
            } else {
                FlagCell(CellState.MIXED, fileCells.flatMap { it.reasons })
            }
            val owner = files.keys.joinToString(", ") { it.substringAfterLast('/') }
            val note = when {
                files.size > 1 -> "Defined separately in ${files.size} files; inside each of them its own definition applies."
                kind == FlagKind.COMPILER -> "Depends on the compiler's feature-test macros, not on the build type."
                else -> null
            }
            return FlagInfo(name, kind, owner, BuildType.entries.associateWith { merged }, links, if (files.size > 1) perFile else emptyMap(), note)
        }

        private fun cmakeCell(name: String, bt: BuildType, profiles: List<ResolvedProfile>): FlagCell {
            val matching = profiles.filter { it.buildType == bt }
            val considered = matching.filter { it.enabled }.ifEmpty { matching }
            if (considered.isEmpty()) {
                return FlagCell(CellState.UNKNOWN, listOf(CellReason("no CMake profile builds ${bt.displayName}")))
            }
            val values = considered.map { p -> p to (p.flags[name] ?: ProfileFlagValue(CellState.UNKNOWN, "not evaluated", null)) }
            val states = values.map { it.second.state }.distinct()
            val state = when {
                states.size == 1 -> states.single()
                CellState.UNKNOWN in states -> CellState.UNKNOWN
                else -> CellState.MIXED
            }
            return FlagCell(state, values.map { (p, v) ->
                CellReason("${p.name}${if (p.enabled) "" else " (disabled profile)"}: ${v.state.description} — ${v.reason}", v.link)
            })
        }

        fun resolveProfile(inputs: ProfileInputs, cmake: CMakeFlagScript?, cmakePath: String?): ResolvedProfile {
            val used = linkedMapOf<String, VariableValue>()
            fun lookup(variable: String): VariableValue? {
                val value = inputs.generationDefines[variable]?.let { VariableValue(variable, it, VariableOrigin.GENERATION_OPTIONS) }
                    ?: (if (variable == "CMAKE_BUILD_TYPE") inputs.buildType?.let { VariableValue(variable, it, VariableOrigin.PROFILE_BUILD_TYPE) } else null)
                    ?: inputs.cache?.get(variable)?.let { VariableValue(variable, it, VariableOrigin.CMAKE_CACHE) }
                    ?: cmake?.options?.get(variable)?.let { VariableValue(variable, it.default, VariableOrigin.OPTION_DEFAULT) }
                value?.let { if (variable != "CMAKE_BUILD_TYPE") used[variable] = it }
                return value
            }

            fun evaluate(definitions: List<CMakeDefinition>): Triple<CMakeDefinition?, Boolean, String> {
                // The last definition whose branch is taken wins; one unreadable branch makes the result unknown.
                var taken: CMakeDefinition? = null
                var unknown = false
                val read = linkedMapOf<String, VariableValue?>()
                for (definition in definitions) {
                    val evaluator = CMakeConditionEvaluator(::lookup)
                    val results = definition.guards.map { guard -> evaluator.evaluate(guard.expression)?.let { it == guard.mustBe } }
                    val applies = when {
                        results.any { it == false } -> false
                        results.any { it == null } -> null
                        else -> true
                    }
                    read.putAll(evaluator.used)
                    when (applies) {
                        true -> taken = definition
                        null -> unknown = true
                        false -> {}
                    }
                }
                val explanation = read.entries.joinToString(", ") { (variable, value) ->
                    if (value == null) "$variable unknown" else "$variable=${value.value} (${value.origin.label})"
                }
                return Triple(taken, unknown, explanation)
            }

            var buildType: BuildType? = null
            var buildTypeReason: String
            val buildTypeDefs = cmake?.definitionsOf("NIHIL_BUILD_TYPE").orEmpty()
            if (buildTypeDefs.isNotEmpty()) {
                val (taken, unknown, reason) = evaluate(buildTypeDefs)
                buildType = if (unknown) null else taken?.let { BuildType.fromMacro(it.value) }
                buildTypeReason = when {
                    buildType != null -> "${buildType.displayName}: $reason"
                    unknown -> "can't tell: $reason"
                    else -> "CMake would stop with \"Unsupported build type ${inputs.buildType}\""
                }
            } else {
                val tests = lookup("NIHIL_ENABLE_TESTS")?.let { CMakeValues.truth(it.value) }
                buildType = BuildType.fallback(inputs.generationDefines["CMAKE_BUILD_TYPE"] ?: inputs.buildType, tests)
                buildTypeReason = "built-in mapping (NIHIL_BUILD_TYPE definitions not found in NihilFlags.cmake)"
            }

            val flagValues = linkedMapOf<String, ProfileFlagValue>()
            if (cmake != null && cmakePath != null) {
                for (name in cmake.flagNames) {
                    val (taken, unknown, reason) = evaluate(cmake.definitionsOf(name))
                    flagValues[name] = when {
                        unknown -> ProfileFlagValue(CellState.UNKNOWN, "can't evaluate: $reason", null)
                        taken == null -> ProfileFlagValue(CellState.UNDEFINED, "no branch defines it ($reason)", null)
                        else -> {
                            val state = when (DefineValue.parse(taken.value)) {
                                DefineValue.ENABLED -> CellState.ENABLED
                                DefineValue.DISABLED -> CellState.DISABLED
                                DefineValue.OTHER -> CellState.UNKNOWN
                            }
                            ProfileFlagValue(state, reason.ifEmpty { "unconditional" }, SourceLink(cmakePath, taken.line))
                        }
                    }
                }
            }

            return ResolvedProfile(inputs.name, inputs.enabled, buildType, buildTypeReason, used.values.toList(), flagValues)
        }
    }
}
