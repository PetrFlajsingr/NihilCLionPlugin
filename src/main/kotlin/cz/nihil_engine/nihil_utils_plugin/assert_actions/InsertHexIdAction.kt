package cz.nihil_engine.nihil_utils_plugin.assert_actions

import cz.nihil_engine.nihil_utils_plugin.base_actions.InsertOrOverwriteTextAction
import cz.nihil_engine.nihil_utils_plugin.generateRandomAssertID


class InsertHexIdAction : InsertOrOverwriteTextAction {
    constructor() : super()
    constructor(text: String?, description: String?) : super(text, description)

    override val textToInsert get() = generateRandomAssertID()
    override val actionName get() = "InsertID"

}