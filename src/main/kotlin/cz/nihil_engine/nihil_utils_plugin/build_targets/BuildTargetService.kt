package cz.nihil_engine.nihil_utils_plugin.build_targets

import com.intellij.execution.ExecutionTarget
import com.intellij.execution.ExecutionTargetManager
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.jetbrains.cidr.cpp.cmake.CMakeSettings
import com.jetbrains.cidr.cpp.cmake.presets.CMakePresetLoader
import com.jetbrains.cidr.cpp.cmake.presets.ConfigurePreset
import com.jetbrains.cidr.cpp.cmake.presets.Schema
import com.jetbrains.cidr.cpp.execution.CMakeBuildProfileExecutionTarget
import cz.nihil_engine.nihil_utils_plugin.project.NihilProjectConfigService

/** What the combos show: the grid and where the active CMake profile sits in it. */
data class BuildTargetState(
    val grid: BuildTargetGrid,
    /** Active CMake profile, or null when the active execution target isn't a CMake profile. */
    val profileName: String?,
    val entry: GridEntry?,
    val configProblems: List<String>,
)

/**
 * Reads the variant x target grid from the CMake profiles and applies a selection by switching
 * ExecutionTargetManager's active target: the same state CLion's own profile combo writes.
 */
@Service(Service.Level.PROJECT)
class BuildTargetService(private val project: Project) {

    private val log = Logger.getInstance(BuildTargetService::class.java)

    /**
     * Rebuilt on every call: a handful of profiles, cheap to map, and the result is always in sync with
     * CMakeSettings without a CMakeSettingsListener cache to invalidate.
     */
    fun grid(): BuildTargetGrid {
        val config = NihilProjectConfigService.getInstance(project).config.buildTargets
        val cmakeProfiles = CMakeSettings.getInstance(project).profiles
        val presets = if (cmakeProfiles.any { it.fromPreset }) presetSchema()?.configurePresetsMap.orEmpty() else emptyMap()
        val profiles = cmakeProfiles.map {
            ProfileInfo(
                name = it.name,
                enabled = it.enabled,
                generationOptions = it.generationOptions.orEmpty(),
                displayName = it.displayName,
                presetCacheVariables = if (it.fromPreset) cacheVariablesOf(presets[it.name]) else emptyMap(),
            )
        }
        return BuildTargetGrid.build(profiles, config) { CMakeSettings.getOptionsList(it) }
    }

    @Volatile
    private var cachedSchema: Schema? = null

    /** The project's CMake presets, parsed by CLion's loader; re-parsed only when a preset file changed. */
    private fun presetSchema(): Schema? {
        cachedSchema?.takeIf { it.isUpToDate(project) }?.let { return it }
        return try {
            project.service<CMakePresetLoader>().loadProjectSchema().also { cachedSchema = it }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            log.debug("CMake presets not loaded: ${e.message}")
            null
        }
    }

    private fun cacheVariablesOf(preset: ConfigurePreset<*>?): Map<String, String> =
        runCatching { preset?.cacheVariables.orEmpty().mapValues { (_, v) -> v.value.toString() } }
            .getOrDefault(emptyMap())

    /**
     * Flips a profile's enabled flag the way CLion's CMake settings do; CLion then reloads the CMake project.
     * Refuses to disable the last enabled profile.
     */
    fun toggleEnabled(profileName: String) {
        val settings = CMakeSettings.getInstance(project)
        val profile = settings.profiles.firstOrNull { it.name == profileName } ?: return
        if (profile.enabled && settings.activeProfiles.size <= 1) return
        settings.profiles = CMakeSettings.toggleProfileInList(profile, settings.profiles)
    }

    fun isEnabled(profileName: String): Boolean =
        CMakeSettings.getInstance(project).profiles.firstOrNull { it.name == profileName }?.enabled == true

    fun canToggle(profileName: String): Boolean {
        val settings = CMakeSettings.getInstance(project)
        val profile = settings.profiles.firstOrNull { it.name == profileName } ?: return false
        return !profile.enabled || settings.activeProfiles.size > 1
    }

    fun current(): BuildTargetState {
        val grid = grid()
        val profileName = CMakeBuildProfileExecutionTarget.getProfileName(ExecutionTargetManager.getInstance(project).activeTarget)
        return BuildTargetState(
            grid = grid,
            profileName = profileName,
            entry = profileName?.let { grid.entryForProfile(it) },
            configProblems = NihilProjectConfigService.getInstance(project).config.problems,
        )
    }

    /**
     * Selects [entry]'s profile together with a run configuration that can run with it, first of: the one last
     * used with this profile, the selected one, the one last used with the entry's target, the first that can.
     */
    fun select(entry: GridEntry) {
        if (!entry.enabled) return
        val runManager = RunManager.getInstance(project)
        val etm = ExecutionTargetManager.getInstance(project)
        val memory = LastRunConfigPerTarget.getInstance(project)

        fun profileTargetFor(settings: RunnerAndConfigurationSettings?): ExecutionTarget? =
            settings?.let { s ->
                etm.getTargetsFor(s.configuration)
                    .firstOrNull { CMakeBuildProfileExecutionTarget.getProfileName(it) == entry.profileName }
            }

        val current = runManager.selectedConfiguration
        var settings: RunnerAndConfigurationSettings? = null
        var target: ExecutionTarget? = null
        val candidates = listOfNotNull(memory.forProfile(entry), current, memory.forTarget(entry)) + runManager.allSettings
        for (candidate in candidates) {
            target = profileTargetFor(candidate) ?: continue
            settings = candidate
            break
        }

        if (settings == null || target == null) {
            log.info("No run configuration can run with profile ${entry.profileName}")
            NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification(
                    "No run configuration for \"${entry.displayName}\"",
                    "None of the run configurations can run with this profile. Reload the CMake project if the profile was just added.",
                    NotificationType.WARNING,
                )
                .notify(project)
            return
        }

        memory.withoutRecording {
            if (settings !== current) runManager.selectedConfiguration = settings
            etm.activeTarget = target
        }
        memory.record(entry, settings)
    }

    companion object {
        const val NOTIFICATION_GROUP = "Nihil Build Targets"

        fun getInstance(project: Project): BuildTargetService =
            project.getService(BuildTargetService::class.java)
    }
}
