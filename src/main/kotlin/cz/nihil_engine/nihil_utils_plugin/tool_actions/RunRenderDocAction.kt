package cz.nihil_engine.nihil_utils_plugin.tool_actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.IconLoader
import cz.nihil_engine.nihil_utils_plugin.base_actions.RunToolAction
import cz.nihil_engine.nihil_utils_plugin.config.RunConfigExtractor
import cz.nihil_engine.nihil_utils_plugin.renderdoc.RenderDocCapGenerator
import java.io.File

class RunRenderDocAction : RunToolAction(
    "RenderDoc",
    "Open RenderDoc",
    IconLoader.getIcon("/icons/renderdoc.svg", RunTracyAction::class.java)
) {
    private val log = Logger.getInstance("RunRenderDocAction")

    override val toolArg: String
        get() = "renderdoc"

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val basePath = project.basePath ?: return

        var capPath = ""
        RunConfigExtractor.extract(e.project!!)?.let {
            capPath = RenderDocCapGenerator.generate(it).path
        }


        val pb = ProcessBuilder(
            "python",
            "tools/tool_runner.py",
            toolArg,
            *extraArgs.toTypedArray(),
            capPath,
        ).directory(File(basePath))

        log.info("Launching process with args: ${pb.command().joinToString(" ")}")

        pb.start()

    }
}