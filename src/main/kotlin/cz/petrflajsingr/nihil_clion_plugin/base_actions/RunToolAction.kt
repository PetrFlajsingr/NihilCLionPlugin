package cz.petrflajsingr.nihil_clion_plugin.base_actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import java.io.File
import javax.swing.Icon

abstract class RunToolAction(
    text: String,
    description: String,
    icon: Icon?
) : AnAction(text, description, icon) {

    protected abstract val toolArg: String

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val basePath = project.basePath ?: return

        ProcessBuilder(
            "python",
            "tools/tool_runner.py",
            toolArg
        )
            .directory(File(basePath))
            .start()
    }
}