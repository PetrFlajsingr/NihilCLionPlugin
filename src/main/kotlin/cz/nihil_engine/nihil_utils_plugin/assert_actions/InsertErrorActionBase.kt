package cz.nihil_engine.nihil_utils_plugin.assert_actions

import com.intellij.openapi.actionSystem.AnActionEvent
import cz.nihil_engine.nihil_utils_plugin.base_actions.InsertTextAction
import cz.nihil_engine.nihil_utils_plugin.base_actions.cursorHere
import cz.nihil_engine.nihil_utils_plugin.base_actions.select
import cz.nihil_engine.nihil_utils_plugin.generateRandomAssertID

abstract class InsertErrorActionBase : InsertTextAction() {
    abstract val errorName: String
    override val textToInsert
        get() = """%s(%s, LogTemp, "%s");""".format(
            errorName, generateRandomAssertID(),
            if (message.isEmpty()) cursorHere() else select(message),
        )
    open val message get() = ""
    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null
    }
}