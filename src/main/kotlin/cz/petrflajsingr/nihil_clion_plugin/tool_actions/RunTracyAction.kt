package cz.petrflajsingr.nihil_clion_plugin.tool_actions

import com.intellij.openapi.util.IconLoader
import cz.petrflajsingr.nihil_clion_plugin.base_actions.RunToolAction

class RunTracyAction : RunToolAction(
    "Tracy",
    "Open Tracy Profiler",
    IconLoader.getIcon("/icons/tracy.svg", RunTracyAction::class.java)
) {
    override val toolArg: String
        get() = "tracy"
}