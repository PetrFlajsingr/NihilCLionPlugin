package cz.nihil_engine.nihil_utils_plugin.project

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import cz.nihil_engine.nihil_utils_plugin.asserts.IgnoredAssertsToolWindowFactory
import cz.nihil_engine.nihil_utils_plugin.cvars.CVarsToolWindowFactory
import cz.nihil_engine.nihil_utils_plugin.feature_flags.FeatureFlagsToolWindowFactory

/**
 * Shows and hides the tool windows that `.idea/nihil_plugin.toml` switches. Their factories' `shouldBeAvailable` runs
 * only when the project opens, and a window that starts unavailable never creates its content, so a listener there
 * would never hear the feature being turned on.
 */
class NihilToolWindowsAvailability(private val project: Project) : NihilProjectConfigService.Listener {

    override fun configChanged() {
        ApplicationManager.getApplication().invokeLater({
            val manager = ToolWindowManager.getInstance(project)
            for ((id, feature) in TOOL_WINDOWS) {
                manager.getToolWindow(id)?.isAvailable = NihilProjectConfigService.isEnabled(project, feature)
            }
        }, project.disposed)
    }

    companion object {
        val TOOL_WINDOWS = mapOf(
            IgnoredAssertsToolWindowFactory.ID to NihilFeature.IGNORED_ASSERTS,
            CVarsToolWindowFactory.ID to NihilFeature.CVARS,
            FeatureFlagsToolWindowFactory.ID to NihilFeature.FEATURE_FLAGS,
        )
    }
}
