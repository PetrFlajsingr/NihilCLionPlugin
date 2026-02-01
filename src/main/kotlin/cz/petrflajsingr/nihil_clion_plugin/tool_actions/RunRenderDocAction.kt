package cz.petrflajsingr.nihil_clion_plugin.tool_actions

import com.intellij.openapi.util.IconLoader
import cz.petrflajsingr.nihil_clion_plugin.base_actions.RunToolAction

class RunRenderDocAction : RunToolAction(
    "RenderDoc",
    "Open RenderDoc",
    IconLoader.getIcon("/icons/renderdoc.svg", RunTracyAction::class.java)
) {
    override val toolArg: String
        get() = "renderdoc"
}