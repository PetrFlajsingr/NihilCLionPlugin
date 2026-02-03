package cz.nihil_engine.nihil_utils_plugin.tool_actions

import com.intellij.openapi.util.IconLoader
import cz.nihil_engine.nihil_utils_plugin.base_actions.RunToolAction

class RunTracyAction : RunToolAction(
    "Tracy",
    "Open Tracy Profiler",
    IconLoader.getIcon("/icons/tracy.svg", RunTracyAction::class.java)
) {
    override val toolArg: String
        get() = "tracy"
}