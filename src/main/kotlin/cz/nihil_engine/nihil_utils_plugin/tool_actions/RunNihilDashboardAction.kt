package cz.nihil_engine.nihil_utils_plugin.tool_actions

import cz.nihil_engine.nihil_utils_plugin.base_actions.RunToolAction
import cz.nihil_engine.nihil_utils_plugin.util.scaledIcon

class RunNihilDashboardAction : RunToolAction(
    "Nihil Tools Dashboard",
    "Open Nihil Tools Dashboard",
    scaledIcon("/icons/dashboard.svg", RunNihilDashboardAction::class.java)
) {
    override val toolArg: String
        get() = "dashboard"
}
