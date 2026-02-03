package cz.nihil_engine.nihil_utils_plugin.assert_actions

import com.intellij.openapi.actionSystem.AnActionEvent
import cz.nihil_engine.nihil_utils_plugin.base_actions.InsertTextAction
import cz.nihil_engine.nihil_utils_plugin.generateRandomAssertID


abstract class InsertAssertActionBase : InsertTextAction() {
    abstract val assertName: String
    override val textToInsert
        get() = """%s(%s, (EXPRESSION), LogTemp, "Message");""".format(
            assertName,
            generateRandomAssertID()
        )
    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null
    }
}