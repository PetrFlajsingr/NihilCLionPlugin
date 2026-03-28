package cz.nihil_engine.nihil_utils_plugin.args

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import cz.nihil_engine.nihil_utils_plugin.RunConfigTargetResolver
import java.awt.Point

class ShowNihilArgsPopupAction : AnAction(
    "Nihil Args",
    "Configure target-specific program arguments",
    AllIcons.General.Settings,
), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val panel = NihilArgsPanelBuilder.build(project)

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, panel)
            .setTitle("Nihil Args")
            .setFocusable(true)
            .setRequestFocus(true)
            .setMovable(true)
            .setResizable(true)
            .createPopup()

        val component = e.inputEvent?.component
        if (component != null) {
            popup.show(RelativePoint(component, Point(0, component.height)))
        } else {
            popup.showInFocusCenter()
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        val service = NihilArgsConfigService.getInstance(project)
        val targetName = RunConfigTargetResolver.resolve(project)
        val profile = targetName?.let { service.config.findProfile(it) }

        e.presentation.isEnabled = profile != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}