package cz.nihil_engine.nihil_utils_plugin.rtti_actions

import com.intellij.openapi.project.DumbAware
import cz.nihil_engine.nihil_utils_plugin.base_actions.InsertOrOverwriteTextAction
import cz.nihil_engine.nihil_utils_plugin.generateRandomRTTIID

class InsertRTTIID : InsertOrOverwriteTextAction, DumbAware {
    constructor() : super()
    constructor(text: String?, description: String?) : super(text, description)

    override val textToInsert get() = generateRandomRTTIID()

    override val actionName get() = "InsertRTIITypeID"
}