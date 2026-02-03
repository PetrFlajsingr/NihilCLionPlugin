package cz.nihil_engine.nihil_utils_plugin.tool_actions

import com.intellij.openapi.util.IconLoader
import cz.nihil_engine.nihil_utils_plugin.base_actions.RunToolAction

class RunNihilLogViewerAction : RunToolAction(
    "Nihil Log Viewer",
    "Open Nihil Log Viewer",
    IconLoader.getIcon("/icons/logviewer.svg", RunTracyAction::class.java)
) {
    override val toolArg: String
        get() = "logviewer"
}