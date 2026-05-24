package cz.nihil_engine.nihil_utils_plugin.tool_actions

import cz.nihil_engine.nihil_utils_plugin.base_actions.RunToolAction
import cz.nihil_engine.nihil_utils_plugin.util.scaledIcon

class RunNihilLogViewerAction : RunToolAction(
    "Nihil Log Viewer",
    "Open Nihil Log Viewer",
    scaledIcon("/icons/logviewer.png", RunNihilLogViewerAction::class.java)
) {
    override val toolArg: String
        get() = "logviewer"
}