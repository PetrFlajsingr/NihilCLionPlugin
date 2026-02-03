package cz.nihil_engine.nihil_utils_plugin.base_actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.DumbAware

abstract class InsertOrOverwriteTextAction : InsertTextAction, DumbAware {
    constructor() : super()
    constructor(text: String?, description: String?) : super(text, description)

    override fun actionPerformed(event: AnActionEvent) {
        val editor = event.getData(CommonDataKeys.EDITOR)
        val project = event.getData(CommonDataKeys.PROJECT)
        if (editor == null || project == null) { return;}
        val document = editor.document
        val caret = editor.caretModel.primaryCaret

        WriteCommandAction
            .writeCommandAction(project)
            .withName(actionName)
            .withGlobalUndo()
            .run<Exception> {
                document.replaceString(caret.selectionStart, caret.selectionEnd, textToInsert)
            }
    }
}