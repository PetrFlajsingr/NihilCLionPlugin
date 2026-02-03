package cz.nihil_engine.nihil_utils_plugin.tool_actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.IconLoader
import cz.nihil_engine.nihil_utils_plugin.base_actions.RunToolAction
import cz.nihil_engine.nihil_utils_plugin.config.RunConfigExtractor
import cz.nihil_engine.nihil_utils_plugin.renderdoc.RenderDocCapGenerator
import java.io.File

class RunRenderDocAction : RunToolAction(
    "RenderDoc",
    "Open RenderDoc",
    IconLoader.getIcon("/icons/renderdoc.svg", RunTracyAction::class.java)
) {
    private val log = Logger.getInstance("RunRenderDocAction")

    override val toolArg: String
        get() = "renderdoc"

    override fun getExtraArgs(e: AnActionEvent): List<String> {
        var capPath = ""
        RunConfigExtractor.extract(e.project!!)?.let {
            capPath = RenderDocCapGenerator.generate(it).path
        }
        if (capPath.isNotEmpty()) {
            return listOf(capPath)
        }
        return listOf()
    }
}