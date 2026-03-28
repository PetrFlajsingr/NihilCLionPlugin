package cz.nihil_engine.nihil_utils_plugin

import com.intellij.execution.RunManager
import com.intellij.openapi.project.Project
import com.jetbrains.cidr.cpp.execution.CMakeAppRunConfiguration

object RunConfigTargetResolver {

    /**
     * Resolve the current run configuration's CMake target name.
     * Falls back to the config name if not a CMake configuration.
     */
    fun resolve(project: Project): String? {
        val selected = RunManager.getInstance(project).selectedConfiguration ?: return null
        val config = selected.configuration

        //if (config is CMakeAppRunConfiguration) {
        //    val targetName = config.cMakeTarget?.name ?: config.name
        //    if (targetName.isNotBlank()) return targetName
        //}

        return selected.name
    }
}
