package cz.nihil_engine.nihil_utils_plugin.args

import com.intellij.execution.ExecutionListener
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.diagnostic.Logger
import com.jetbrains.cidr.cpp.execution.CMakeAppRunConfiguration

/**
 * Injects nihil_args.toml arguments into the program parameters at launch time.
 * Uses CLion's CMakeAppRunConfiguration API directly.
 *
 * The injected args are tagged with markers so they can be cleanly stripped
 * after the process terminates — the stored run configuration is never
 * permanently modified.
 */
class NihilArgsExecutionListener : ExecutionListener {

    private val log = Logger.getInstance(NihilArgsExecutionListener::class.java)

    override fun processStartScheduled(executorId: String, env: ExecutionEnvironment) {
        val config = env.runProfile as? CMakeAppRunConfiguration ?: return
        val project = env.project
        val service = NihilArgsConfigService.getInstance(project)

        val targetName = config.cMakeTarget?.name ?: config.name

        val extraArgs = service.buildCommandLineArgs(targetName)
        if (extraArgs.isEmpty()) return

        val existing = config.programParameters ?: ""
        val injected = extraArgs.joinToString(" ")
        val tagged = "$MARKER_START $injected $MARKER_END"

        val combined = if (existing.contains(MARKER_START)) {
            // Re-run — replace the previously tagged section
            existing.replace(MARKER_REGEX, tagged)
        } else {
            if (existing.isBlank()) tagged else "$existing $tagged"
        }

        config.programParameters = combined
        log.info("Injected nihil args for '$targetName': $injected")
    }

    override fun processTerminated(
        executorId: String,
        env: ExecutionEnvironment,
        handler: ProcessHandler,
        exitCode: Int,
    ) {
        val config = env.runProfile as? CMakeAppRunConfiguration ?: return
        val current = config.programParameters ?: return

        if (current.contains(MARKER_START)) {
            config.programParameters = current.replace(MARKER_CLEANUP_REGEX, "").trim()
        }
    }

    companion object {
        private const val MARKER_START = ""// "/*nihil-args*/"
        private const val MARKER_END = ""// "/*end-nihil-args*/"
        private val MARKER_REGEX = Regex("${Regex.escape(MARKER_START)}.*?${Regex.escape(MARKER_END)}")
        private val MARKER_CLEANUP_REGEX = Regex("\\s*${Regex.escape(MARKER_START)}.*?${Regex.escape(MARKER_END)}\\s*")
    }
}