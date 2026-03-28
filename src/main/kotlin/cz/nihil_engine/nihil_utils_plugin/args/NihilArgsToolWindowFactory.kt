package cz.nihil_engine.nihil_utils_plugin.args

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory

class NihilArgsToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = NihilArgsPanel(project)
        val content = toolWindow.contentManager.factory
            .createContent(panel.component, null, false)
        toolWindow.contentManager.addContent(content)
    }
}