package cz.nihil_engine.nihil_utils_plugin.tool_actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.util.IconLoader
import cz.nihil_engine.nihil_utils_plugin.base_actions.RunToolAction
import cz.nihil_engine.nihil_utils_plugin.config.RunConfigExtractor
import cz.nihil_engine.nihil_utils_plugin.renderdoc.RenderDocCapGenerator

class RunRenderDocAction : RunToolAction(
    "RenderDoc",
    "Open RenderDoc",
    IconLoader.getIcon("/icons/renderdoc.svg", RunRenderDocAction::class.java)
) {
    override val toolArg: String
        get() = "renderdoc"

    override fun getExtraArgs(e: AnActionEvent): List<String> {
        val project = e.project ?: return listOf()
        val capPath = RunConfigExtractor.extract(project)?.let { RenderDocCapGenerator.generate(it).path }
        return listOfNotNull(capPath)
    }
}