package cz.nihil_engine.nihil_utils_plugin.assert_actions

import com.intellij.openapi.actionSystem.AnActionEvent
import cz.nihil_engine.nihil_utils_plugin.base_actions.InsertTextAction
import cz.nihil_engine.nihil_utils_plugin.base_actions.select
import cz.nihil_engine.nihil_utils_plugin.generateRandomAssertID


abstract class InsertAssertActionBase : InsertTextAction() {
    abstract val assertName: String
    override val textToInsert
        get() = """%s(%s, (%s), LogTemp, "Message");""".format(
            assertName,
            generateRandomAssertID(),
            select("EXPRESSION"),
        )
    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null
    }
}