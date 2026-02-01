package cz.petrflajsingr.nihil_clion_plugin.assert_actions

import cz.petrflajsingr.nihil_clion_plugin.base_actions.InsertTextAction

class InsertNotImplementedErrorAction : InsertTextAction() {
    override val textToInsert get() = "NIHIL_NOT_IMPLEMENTED();"
    override val actionName get() = "InsertNotImplementedError"
}