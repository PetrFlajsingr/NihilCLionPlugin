package cz.nihil_engine.nihil_utils_plugin.build_targets

import cz.nihil_engine.nihil_utils_plugin.project.BuildTargetsConfig

/** The parts of a CMake profile the grid needs; decoupled from CMakeSettings.Profile so it can be tested. */
data class ProfileInfo(
    val name: String,
    val enabled: Boolean,
    val generationOptions: String,
)

/** A profile placed at one (variant, target) cell. */
data class GridEntry(
    val profileName: String,
    val variant: String,
    val target: String,
    val enabled: Boolean,
)

/** A profile whose name and options disagree, or that couldn't be placed. Shown, never silently remapped. */
data class GridWarning(
    val profileName: String,
    val message: String,
)

/**
 * Maps CMake profiles to (variant, target) pairs.
 *
 * - Target: `-D<cmakeVariable>=X` in the generation options; if absent, the default target.
 * - Variant: the profile name with the target part removed, using the configured pattern
 *   (default `{variant} {target}`). Default-target profiles have no target part.
 * - A profile whose name says one target and whose options say another stays at the options' target,
 *   under its full name as the variant, with a warning.
 */
class BuildTargetGrid(
    val config: BuildTargetsConfig,
    val entries: List<GridEntry>,
    val warnings: List<GridWarning>,
) {
    val targets: List<String> get() = config.targets

    /** Variants in profile order. */
    val variants: List<String> = entries.map { it.variant }.distinct()

    private val byPair = entries.associateBy { it.variant to it.target }
    private val byProfile = entries.associateBy { it.profileName }

    fun entry(variant: String, target: String): GridEntry? = byPair[variant to target]

    fun entryForProfile(profileName: String): GridEntry? = byProfile[profileName]

    fun warningsFor(profileName: String): List<GridWarning> = warnings.filter { it.profileName == profileName }

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
                val rawOption = splitOptions(profile.generationOptions)
                    .mapNotNull { optionPattern.find(it)?.groupValues?.get(1) }
                    .lastOrNull()
                    ?.trim()?.removeSurrounding("\"")
                val optionTarget = if (rawOption == null) config.defaultTarget else canonicalTarget(config, rawOption)
                if (optionTarget == null) {
                    warnings += GridWarning(
                        profile.name,
                        "${config.cmakeVariable}=$rawOption is not one of ${config.targets.joinToString(", ")}",
                    )
                    continue
                }

                val match = namePattern.matchEntire(profile.name)
                val nameTarget = match?.groups?.get("target")?.value?.let { canonicalTarget(config, it) }
                val nameVariant = match?.groups?.get("variant")?.value

                val variant = if ((nameTarget ?: config.defaultTarget) == optionTarget) {
                    if (nameTarget != null && nameVariant != null) nameVariant else profile.name
                } else {
                    warnings += GridWarning(
                        profile.name,
                        "name says ${nameTarget ?: config.defaultTarget}, ${config.cmakeVariable} says $optionTarget " +
                            "(listed as variant \"${profile.name}\", target $optionTarget)",
                    )
                    profile.name
                }

                val clash = entries.firstOrNull { it.variant == variant && it.target == optionTarget }
                if (clash != null) {
                    warnings += GridWarning(
                        profile.name,
                        "same pair ($variant, $optionTarget) as profile \"${clash.profileName}\"; ignored",
                    )
                    continue
                }
                entries += GridEntry(profile.name, variant, optionTarget, profile.enabled)
            }

            return BuildTargetGrid(config, entries, warnings)
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
