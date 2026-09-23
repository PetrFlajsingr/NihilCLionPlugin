package cz.nihil_engine.nihil_utils_plugin.build_targets

import com.intellij.execution.ExecutionTarget
import com.intellij.execution.ExecutionTargetManager
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.jetbrains.cidr.cpp.cmake.CMakeSettings
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
        val profiles = CMakeSettings.getInstance(project).profiles.map {
            ProfileInfo(name = it.name, enabled = it.enabled, generationOptions = it.generationOptions.orEmpty())
        }
        return BuildTargetGrid.build(profiles, config) { CMakeSettings.getOptionsList(it) }
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
     * Selects [entry]'s profile. When the selected run configuration can't run with that profile, switches to
     * the run configuration last used with the entry's target, or else to the first one that can.
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
        var settings = current
        var target = profileTargetFor(current)
        if (target == null) {
            val candidates = listOfNotNull(memory.get(entry.target)) + runManager.allSettings
            for (candidate in candidates) {
                target = profileTargetFor(candidate) ?: continue
                settings = candidate
                break
            }
        }

        if (settings == null || target == null) {
            log.info("No run configuration can run with profile ${entry.profileName}")
            NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification(
                    "No run configuration for \"${entry.profileName}\"",
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
        memory.record(entry.target, settings)
    }

    companion object {
        const val NOTIFICATION_GROUP = "Nihil Build Targets"

        fun getInstance(project: Project): BuildTargetService =
            project.getService(BuildTargetService::class.java)
    }
}
