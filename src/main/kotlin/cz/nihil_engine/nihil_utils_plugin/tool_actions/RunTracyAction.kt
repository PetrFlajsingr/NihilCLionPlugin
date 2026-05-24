package cz.nihil_engine.nihil_utils_plugin.tool_actions

import cz.nihil_engine.nihil_utils_plugin.base_actions.RunToolAction
import cz.nihil_engine.nihil_utils_plugin.util.scaledIcon

class RunTracyAction : RunToolAction(
    "Tracy",
    "Open Tracy Profiler",
    scaledIcon("/icons/tracy.png", RunTracyAction::class.java)
) {
    override val toolArg: String
        get() = "tracy"
}