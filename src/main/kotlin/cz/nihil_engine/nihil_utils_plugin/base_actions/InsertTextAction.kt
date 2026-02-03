package cz.nihil_engine.nihil_utils_plugin.base_actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.DumbAware


abstract class InsertTextAction : AnAction, DumbAware {
    constructor() : super()
    constructor(text: String?, description: String?) : super(text, description, null)

    // using main UI thread
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    abstract val textToInsert: String
    abstract val actionName: String
    override fun actionPerformed(event: AnActionEvent) {
        val editor = event.getData(CommonDataKeys.EDITOR)
        val project = event.getData(CommonDataKeys.PROJECT)
        if (editor == null || project == null) {
            return; }
        val document = editor.document
        val caret = editor.caretModel.primaryCaret


        WriteCommandAction
            .writeCommandAction(project)
            .withName(actionName)
            .withGlobalUndo()
            .run<Exception> {
                document.insertString(caret.offset, textToInsert)
            }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null
    }
}