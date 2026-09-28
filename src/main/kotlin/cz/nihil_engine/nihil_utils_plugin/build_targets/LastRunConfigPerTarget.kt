package cz.nihil_engine.nihil_utils_plugin.build_targets

import com.intellij.execution.ExecutionTarget
import com.intellij.execution.ExecutionTargetListener
import com.intellij.execution.ExecutionTargetManager
import com.intellij.execution.RunManager
import com.intellij.execution.RunManagerListener
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.ide.ActivityTracker
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import cz.nihil_engine.nihil_utils_plugin.project.NihilFeature
import cz.nihil_engine.nihil_utils_plugin.project.NihilProjectConfigService

/**
 * Workspace-level memory of the last run configuration used with each (variant, target) pair, i.e. each profile,
 * and with each build target as the fallback for a pair not used yet.
 * Stored in the project's PropertiesComponent (workspace.xml).
 */
@Service(Service.Level.PROJECT)
class LastRunConfigPerTarget(private val project: Project) {

    /** Set while the plugin itself switches profile and run configuration, so the intermediate states aren't recorded. */
    @Volatile
    private var suspended = false

    /** Last run configuration used with [entry]'s profile. */
    fun forProfile(entry: GridEntry): RunnerAndConfigurationSettings? = find(profileKey(entry.profileName))

    /** Last run configuration used with any profile of [entry]'s target. */
    fun forTarget(entry: GridEntry): RunnerAndConfigurationSettings? = find(targetKey(entry.target))

    fun record(entry: GridEntry, settings: RunnerAndConfigurationSettings) {
        val props = PropertiesComponent.getInstance(project)
        props.setValue(profileKey(entry.profileName), settings.uniqueID)
        props.setValue(targetKey(entry.target), settings.uniqueID)
    }

    private fun find(key: String): RunnerAndConfigurationSettings? {
        val id = PropertiesComponent.getInstance(project).getValue(key) ?: return null
        return RunManager.getInstance(project).allSettings.firstOrNull { it.uniqueID == id }
    }

    fun <T> withoutRecording(block: () -> T): T {
        suspended = true
        try {
            return block()
        } finally {
            suspended = false
        }
    }

    /** Records the selected run configuration under the active profile and its target, if it can run there. */
    fun recordCurrent() {
        if (suspended || !NihilProjectConfigService.isEnabled(project, NihilFeature.BUILD_TARGET_SELECTOR)) return
        val settings = RunManager.getInstance(project).selectedConfiguration ?: return
        val etm = ExecutionTargetManager.getInstance(project)
        val active = etm.activeTarget
        // RunManager fires the selection before ExecutionTargetManager moves to a profile the new
        // configuration supports; recording then would file it under the old target.
        if (etm.getTargetsFor(settings.configuration).none { it.id == active.id }) return
        val entry = BuildTargetService.getInstance(project).current().entry ?: return
        record(entry, settings)
    }

    // The target key predates per-profile memory; keeping it keeps what users already have recorded.
    private fun targetKey(target: String) = "nihil.buildTargets.lastRunConfig.$target"

    private fun profileKey(profileName: String) = "nihil.buildTargets.lastRunConfig.profile.$profileName"

    companion object {
        fun getInstance(project: Project): LastRunConfigPerTarget =
            project.getService(LastRunConfigPerTarget::class.java)
    }
}

/** Keeps [LastRunConfigPerTarget] current and refreshes the combos when CLion changes the profile or configuration. */
class BuildTargetSelectionListener(private val project: Project) : RunManagerListener, ExecutionTargetListener {

    override fun runConfigurationSelected(settings: RunnerAndConfigurationSettings?) = changed()

    override fun activeTargetChanged(newTarget: ExecutionTarget) = changed()

    private fun changed() {
        if (project.isDisposed) return
        LastRunConfigPerTarget.getInstance(project).recordCurrent()
        ActivityTracker.getInstance().inc()
    }
}
