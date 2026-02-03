package cz.nihil_engine.nihil_utils_plugin.assert_actions

import cz.nihil_engine.nihil_utils_plugin.base_actions.InsertTextAction
import cz.nihil_engine.nihil_utils_plugin.generateRandomAssertID

class InsertStaticAssertAction : InsertTextAction() {
    override val textToInsert get() = """NIHIL_STATIC_ASSERT(%s, EXPRESSION, "");""".format(generateRandomAssertID())
    override val actionName get() = "InsertStaticAssert"
}