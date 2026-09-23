package cz.nihil_engine.nihil_utils_plugin.navigation

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages
import cz.nihil_engine.nihil_utils_plugin.asserts.AssertLocator
import cz.nihil_engine.nihil_utils_plugin.asserts.AssertSite

class GoToAssertByIdAction : AnAction("Go to Assert by ID...", "Find and navigate to an assert by its hex ID", null), DumbAware {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val raw = Messages.showInputDialog(
            project,
            "Hex ID (with or without 0x prefix):",
            "Go to Assert by ID",
            Messages.getQuestionIcon(),
            "",
            HexInputValidator,
        ) ?: return

        val id = AssertSite.parseId(raw.trim()) ?: run {
            Messages.showWarningDialog(project, "Not a valid hex ID: $raw", "Go to Assert by ID")
            return
        }

        AssertLocator.navigateToId(project, id)
    }

    private object HexInputValidator : com.intellij.openapi.ui.InputValidator {
        override fun checkInput(input: String?): Boolean {
            if (input.isNullOrBlank()) return false
            return AssertSite.parseId(input.trim()) != null
        }
        override fun canClose(input: String?): Boolean = checkInput(input)
    }
}
