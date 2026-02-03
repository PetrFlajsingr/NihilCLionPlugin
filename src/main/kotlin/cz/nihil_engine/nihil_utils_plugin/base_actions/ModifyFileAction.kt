package cz.nihil_engine.nihil_utils_plugin.base_actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import java.io.File


enum class ModifyFileActionType {
    Create, Remove
}

abstract class ModifyFileAction : AnAction, DumbAware {
    constructor() : super()
    constructor(text: String?, description: String?) : super(text, description, null)

    // using main UI thread
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    abstract val fileContent: String
    abstract val filePath: String
    abstract val actionType: ModifyFileActionType
    abstract val actionName: String
    override fun actionPerformed(event: AnActionEvent) {
        val file = File(filePath)
        if (actionType == ModifyFileActionType.Create) {
            file.writeText(fileContent)
        } else if (actionType == ModifyFileActionType.Remove) {
            if (file.exists()) {
                file.delete()
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null
    }
}