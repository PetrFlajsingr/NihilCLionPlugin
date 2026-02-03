package cz.nihil_engine.nihil_utils_plugin.wrap_actions

import cz.nihil_engine.nihil_utils_plugin.base_actions.WrapSelectionAction

class WrapUnoptimizePragmaAction : WrapSelectionAction() {
    override val wrapStart get() = """#pragma optimize( "", off ) """
    override val wrapEnd get() = """#pragma optimize( "", on ) """
    override val actionName get() = "WrapUnoptimize"
}