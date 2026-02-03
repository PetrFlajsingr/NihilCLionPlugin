package cz.nihil_engine.nihil_utils_plugin.base_actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import cz.nihil_engine.nihil_utils_plugin.config.RunConfigExtractor
import cz.nihil_engine.nihil_utils_plugin.renderdoc.RenderDocCapGenerator
import java.io.File
import javax.swing.Icon

abstract class RunToolAction(
    text: String,
    description: String,
    icon: Icon?
) : AnAction(text, description, icon) {

    protected abstract val toolArg: String

    protected open fun getExtraArgs(e: AnActionEvent): List<String> {
        return listOf()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val basePath = project.basePath ?: return

        ProcessBuilder(
            "python",
            "tools/tool_runner.py",
            toolArg,
            *getExtraArgs(e).toTypedArray(),
        )
            .directory(File(basePath))
            .start()
    }
}