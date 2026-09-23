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
 * Workspace-level memory of the last run configuration used with each build target.
 * Stored in the project's PropertiesComponent (workspace.xml), keyed by target name.
 */
@Service(Service.Level.PROJECT)
class LastRunConfigPerTarget(private val project: Project) {

    /** Set while the plugin itself switches profile and run configuration, so the intermediate states aren't recorded. */
    @Volatile
    private var suspended = false

    fun get(target: String): RunnerAndConfigurationSettings? {
        val id = PropertiesComponent.getInstance(project).getValue(key(target)) ?: return null
        return RunManager.getInstance(project).allSettings.firstOrNull { it.uniqueID == id }
    }

    fun record(target: String, settings: RunnerAndConfigurationSettings) {
        PropertiesComponent.getInstance(project).setValue(key(target), settings.uniqueID)
    }

    fun <T> withoutRecording(block: () -> T): T {
        suspended = true
        try {
            return block()
        } finally {
            suspended = false
        }
    }

    /** Records the selected run configuration under the active profile's target, if it can run there. */
    fun recordCurrent() {
        if (suspended || !NihilProjectConfigService.isEnabled(project, NihilFeature.BUILD_TARGET_SELECTOR)) return
        val settings = RunManager.getInstance(project).selectedConfiguration ?: return
        val etm = ExecutionTargetManager.getInstance(project)
        val active = etm.activeTarget
        // RunManager fires the selection before ExecutionTargetManager moves to a profile the new
        // configuration supports; recording then would file it under the old target.
        if (etm.getTargetsFor(settings.configuration).none { it.id == active.id }) return
        val entry = BuildTargetService.getInstance(project).current().entry ?: return
        record(entry.target, settings)
    }

    private fun key(target: String) = "nihil.buildTargets.lastRunConfig.$target"

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
