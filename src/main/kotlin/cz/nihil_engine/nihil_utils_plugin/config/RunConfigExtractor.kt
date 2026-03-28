package cz.nihil_engine.nihil_utils_plugin.config

import com.intellij.execution.ExecutionTargetManager
import com.intellij.execution.RunManager
import com.intellij.openapi.project.Project
import com.jetbrains.cidr.cpp.execution.CMakeAppRunConfiguration

import com.intellij.openapi.diagnostic.Logger
import com.jetbrains.cidr.cpp.execution.CMakeBuildProfileExecutionTarget
import cz.nihil_engine.nihil_utils_plugin.args.NihilArgsConfigService

data class RunConfigData(
    val exePath: String,
    val args: String,
    val workingDir: String?
)

object RunConfigExtractor {

    private val log = Logger.getInstance("RunConfigExtractor")

    fun extract(project: Project): RunConfigData? {
        val config = selectedCMakeConfig(project) ?: return null
        val profileName = activeCMakeProfileName(project) ?: return null

        log.info("Active CMake profile: $profileName")

        val buildConfig = config.cMakeTarget
            ?.buildConfigurations
            ?.firstOrNull { it.profileName == profileName }
            ?: return null

        val exePath = buildConfig.productFile?.absolutePath ?: return null

        val targetName = config.cMakeTarget?.name ?: config.name
        val nihilArgs = NihilArgsConfigService.getInstance(project)
            .buildCommandLineArgs(targetName)

        val baseArgs = config.programParameters.orEmpty()
        val combinedArgs = if (nihilArgs.isEmpty()) {
            baseArgs
        } else {
            val injected = nihilArgs.joinToString(" ")
            if (baseArgs.isBlank()) injected else "$baseArgs $injected"
        }

        return RunConfigData(
            exePath = exePath,
            args = combinedArgs,
            workingDir = config.workingDirectory
        )
    }

    private fun selectedCMakeConfig(project: Project): CMakeAppRunConfiguration? =
        RunManager.getInstance(project)
            .selectedConfiguration
            ?.configuration as? CMakeAppRunConfiguration

    private fun activeCMakeProfileName(project: Project): String? {
        val execTarget = ExecutionTargetManager
            .getInstance(project)
            .activeTarget as? CMakeBuildProfileExecutionTarget

        return execTarget?.profileName
    }
}