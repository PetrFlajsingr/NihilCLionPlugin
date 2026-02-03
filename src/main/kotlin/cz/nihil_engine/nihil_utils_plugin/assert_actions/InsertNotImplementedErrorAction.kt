package cz.nihil_engine.nihil_utils_plugin.assert_actions

import cz.nihil_engine.nihil_utils_plugin.base_actions.InsertTextAction

class InsertNotImplementedErrorAction : InsertTextAction() {
    override val textToInsert get() = "NIHIL_NOT_IMPLEMENTED();"
    override val actionName get() = "InsertNotImplementedError"
}