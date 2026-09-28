package cz.nihil_engine.nihil_utils_plugin.build_targets

import cz.nihil_engine.nihil_utils_plugin.project.BuildTargetsConfig

/** The parts of a CMake profile the grid needs; decoupled from CMakeSettings.Profile so it can be tested. */
data class ProfileInfo(
    /** Identity: what ExecutionTargetManager and CMakeSettings key on. For a preset profile, the preset's `name`. */
    val name: String,
    val enabled: Boolean,
    val generationOptions: String,
    /** What CLion shows. For a preset profile, the preset's `displayName`; otherwise [name]. */
    val displayName: String = name,
    /** Resolved cache variables of the profile's configure preset (inheritance applied); empty for a non-preset profile. */
    val presetCacheVariables: Map<String, String> = emptyMap(),
)

/** A profile placed at one (variant, target) cell. */
data class GridEntry(
    val profileName: String,
    val variant: String,
    val target: String,
    val enabled: Boolean,
    val displayName: String = profileName,
)

/** A profile whose name and options disagree, or that couldn't be placed. Shown, never silently remapped. */
data class GridWarning(
    val profileName: String,
    val message: String,
    val displayName: String = profileName,
)

/**
 * Maps CMake profiles to (variant, target) pairs.
 *
 * - Target: `-D<cmakeVariable>=X` in the generation options, else the variable in the profile's configure preset;
 *   if absent, the default target.
 * - Variant: the profile's display name with the target part removed, using the configured pattern
 *   (default `{variant} {target}`). Default-target profiles have no target part. The display name is the
 *   preset's `displayName` for a preset profile (`Debug Editor`, not `debug-editor`).
 * - A profile whose name says one target and whose options say another stays at the options' target,
 *   under its full name as the variant, with a warning.
 */
class BuildTargetGrid(
    val config: BuildTargetsConfig,
    val entries: List<GridEntry>,
    val warnings: List<GridWarning>,
    /** Every profile, placed or not, in CMakeSettings order. */
    val profiles: List<ProfileInfo> = emptyList(),
) {
    val targets: List<String> get() = config.targets

    /** Variants in profile order. */
    val variants: List<String> = entries.map { it.variant }.distinct()

    private val byPair = entries.associateBy { it.variant to it.target }
    private val byProfile = entries.associateBy { it.profileName }

    fun entry(variant: String, target: String): GridEntry? = byPair[variant to target]

    fun entryForProfile(profileName: String): GridEntry? = byProfile[profileName]

    fun warningsFor(profileName: String): List<GridWarning> = warnings.filter { it.profileName == profileName }

    fun displayName(profileName: String): String =
        profiles.firstOrNull { it.name == profileName }?.displayName ?: profileName

    fun enabledTargets(variant: String): List<String> =
        targets.filter { entry(variant, it)?.enabled == true }

    fun hasEnabledProfile(variant: String): Boolean = enabledTargets(variant).isNotEmpty()

    /** The profile name the pattern expects for a pair, used to explain a missing cell. */
    fun expectedProfileName(variant: String, target: String): String =
        if (target == config.defaultTarget) variant
        else config.profileName.replace("{variant}", variant).replace("{target}", target)

    /**
     * Target to use after switching to [variant]: keep [currentTarget] when that pair is enabled,
     * else the default target, else the first enabled one.
     */
    fun targetAfterVariantSwitch(variant: String, currentTarget: String?): String? {
        val enabled = enabledTargets(variant)
        return when {
            currentTarget != null && currentTarget in enabled -> currentTarget
            config.defaultTarget in enabled -> config.defaultTarget
            else -> enabled.firstOrNull()
        }
    }

    companion object {
        fun build(
            profiles: List<ProfileInfo>,
            config: BuildTargetsConfig,
            splitOptions: (String) -> List<String>,
        ): BuildTargetGrid {
            val entries = mutableListOf<GridEntry>()
            val warnings = mutableListOf<GridWarning>()
            val namePattern = namePatternRegex(config)
            val optionPattern = Regex("^-D${Regex.escape(config.cmakeVariable)}(?::[A-Za-z]+)?=(.*)$")

            for (profile in profiles) {
                val shownName = profile.displayName
                val rawOption = (splitOptions(profile.generationOptions)
                    .mapNotNull { optionPattern.find(it)?.groupValues?.get(1) }
                    .lastOrNull()
                    ?: profile.presetCacheVariables[config.cmakeVariable])
                    ?.trim()?.removeSurrounding("\"")
                val optionTarget = if (rawOption == null) config.defaultTarget else canonicalTarget(config, rawOption)
                if (optionTarget == null) {
                    warnings += GridWarning(
                        profile.name,
                        "${config.cmakeVariable}=$rawOption is not one of ${config.targets.joinToString(", ")}",
                        shownName,
                    )
                    continue
                }

                val match = namePattern.matchEntire(shownName)
                val nameTarget = match?.groups?.get("target")?.value?.let { canonicalTarget(config, it) }
                val nameVariant = match?.groups?.get("variant")?.value

                val variant = if ((nameTarget ?: config.defaultTarget) == optionTarget) {
                    if (nameTarget != null && nameVariant != null) nameVariant else shownName
                } else {
                    warnings += GridWarning(
                        profile.name,
                        "name says ${nameTarget ?: config.defaultTarget}, ${config.cmakeVariable} says $optionTarget " +
                            "(listed as variant \"$shownName\", target $optionTarget)",
                        shownName,
                    )
                    shownName
                }

                val clash = entries.firstOrNull { it.variant == variant && it.target == optionTarget }
                if (clash != null) {
                    warnings += GridWarning(
                        profile.name,
                        "same pair ($variant, $optionTarget) as profile \"${clash.displayName}\"; ignored",
                        shownName,
                    )
                    continue
                }
                entries += GridEntry(profile.name, variant, optionTarget, profile.enabled, shownName)
            }

            return BuildTargetGrid(config, entries, warnings, profiles)
        }

        private fun canonicalTarget(config: BuildTargetsConfig, value: String): String? =
            config.targets.firstOrNull { it.equals(value, ignoreCase = true) }

        private fun namePatternRegex(config: BuildTargetsConfig): Regex {
            val targetAlternatives = config.targets.joinToString("|") { Regex.escape(it) }
            val regex = buildString {
                var rest = config.profileName
                while (rest.isNotEmpty()) {
                    when {
                        rest.startsWith("{variant}") -> { append("(?<variant>.+?)"); rest = rest.removePrefix("{variant}") }
                        rest.startsWith("{target}") -> { append("(?<target>$targetAlternatives)"); rest = rest.removePrefix("{target}") }
                        else -> {
                            val next = listOf(rest.indexOf("{variant}"), rest.indexOf("{target}"))
                                .filter { it > 0 }.minOrNull() ?: rest.length
                            append(Regex.escape(rest.substring(0, next)))
                            rest = rest.substring(next)
                        }
                    }
                }
            }
            return Regex(regex, RegexOption.IGNORE_CASE)
        }
    }
}
