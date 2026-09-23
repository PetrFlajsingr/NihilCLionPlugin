package cz.nihil_engine.nihil_utils_plugin.asserts

import com.intellij.execution.ExecutionListener
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.project.Project
import cz.nihil_engine.nihil_utils_plugin.project.NihilFeature
import cz.nihil_engine.nihil_utils_plugin.project.NihilProjectConfigService

class IgnoreListDiscoveryListener(private val project: Project) : ExecutionListener {

    override fun processStarting(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        if (NihilProjectConfigService.isEnabled(project, NihilFeature.IGNORED_ASSERTS)) {
            IgnoreListService.getInstance(project).watchOutput(handler)
        }
    }
}
