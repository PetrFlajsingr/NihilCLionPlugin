package cz.nihil_engine.nihil_utils_plugin.base_actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import cz.nihil_engine.nihil_utils_plugin.settings.NihilPluginSettings
import java.io.File
import java.util.concurrent.TimeUnit
import javax.swing.Icon

abstract class RunToolAction(
    text: String,
    description: String,
    icon: Icon?
) : AnAction(text, description, icon) {

    private val log = Logger.getInstance(RunToolAction::class.java)

    protected abstract val toolArg: String

    protected open fun getExtraArgs(e: AnActionEvent): List<String> = listOf()

    override fun actionPerformed(e: AnActionEvent) {
        val debug = NihilPluginSettings.getInstance().isDebugLoggingEnabled
        val project = e.project ?: run {
            if (debug) log.warn("[$toolArg] actionPerformed: no project in event")
            return
        }
        val basePath = project.basePath ?: run {
            if (debug) log.warn("[$toolArg] actionPerformed: project has no basePath")
            return
        }

        val cmd = listOf("python", "tools/tool_runner.py", toolArg) + getExtraArgs(e)
        if (debug) {
            log.info("[$toolArg] launching: $cmd in $basePath")
            val script = File(basePath, "tools/tool_runner.py")
            if (!script.exists()) log.warn("[$toolArg] script not found: ${script.absolutePath}")
        }
        try {
            val process = ProcessBuilder(cmd)
                .directory(File(basePath))
                .redirectErrorStream(true)
                .start()
            if (debug) {
                ApplicationManager.getApplication().executeOnPooledThread {
                    val exited = process.waitFor(5, TimeUnit.SECONDS)
                    if (exited) {
                        val output = process.inputStream.bufferedReader().readText()
                        log.warn("[$toolArg] process exited quickly (code=${process.exitValue()})${if (output.isNotBlank()) ":\n$output" else " (no output)"}")
                    } else {
                        log.info("[$toolArg] process still running after 5s — looks OK")
                    }
                }
            }
        } catch (ex: Exception) {
            log.error("[$toolArg] failed to start process", ex)
        }
    }
}