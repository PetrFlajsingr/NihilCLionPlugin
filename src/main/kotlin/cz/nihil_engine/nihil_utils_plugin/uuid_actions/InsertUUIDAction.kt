package cz.nihil_engine.nihil_utils_plugin.uuid_actions

import cz.nihil_engine.nihil_utils_plugin.base_actions.InsertTextAction
import java.util.*

class InsertUUIDAction : InsertTextAction() {
    override val textToInsert get() = """"%s"""".format(UUID.randomUUID().toString())
    override val actionName get() = "InsertUUID"
}