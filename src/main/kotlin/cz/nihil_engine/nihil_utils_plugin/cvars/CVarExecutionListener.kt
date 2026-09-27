package cz.nihil_engine.nihil_utils_plugin.cvars

import com.intellij.execution.ExecutionListener
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.project.Project
import com.jetbrains.cidr.cpp.execution.CLionRunConfiguration
import cz.nihil_engine.nihil_utils_plugin.project.NihilFeature
import cz.nihil_engine.nihil_utils_plugin.project.NihilProjectConfigService

/** Connects [CVarLiveService] to the console control server of an app started from the IDE (run or debug). */
class CVarExecutionListener(private val project: Project) : ExecutionListener {

    override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        if (!NihilProjectConfigService.isEnabled(project, NihilFeature.CVARS)) return
        CVarLiveService.getInstance(project).processStarted(handler, env.runProfile.name, retry = env.runProfile is CLionRunConfiguration<*, *>)
    }
}
