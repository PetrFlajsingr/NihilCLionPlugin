package cz.nihil_engine.nihil_utils_plugin.scaffold

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import cz.nihil_engine.nihil_utils_plugin.project.NihilFeature
import cz.nihil_engine.nihil_utils_plugin.project.NihilProjectConfigService
import java.io.File

/** File | New and the project view's New menu: opens [NewNihilModuleDialog]. Shown in projects with `new_module`. */
class NewNihilModuleAction : DumbAwareAction(
    "Nihil Library, App or Tool…",
    "Create a NihilEngine library, interface library, application or tool from the project's templates",
    AllIcons.Nodes.Module,
) {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = NihilProjectConfigService.isEnabled(e.project, NihilFeature.NEW_MODULE)
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        if (!File(project.basePath.orEmpty(), "src").isDirectory) {
            Messages.showErrorDialog(project, "The project has no src/ directory to add to.", "New Nihil Module")
            return
        }
        NewNihilModuleDialog(project).show()
    }
}
