package cz.nihil_engine.nihil_utils_plugin.project

import com.intellij.execution.ExecutionTargetManager
import com.intellij.openapi.project.Project
import com.jetbrains.cidr.cpp.cmake.CMakeSettings
import com.jetbrains.cidr.cpp.cmake.workspace.CMakeProfileInfo
import com.jetbrains.cidr.cpp.cmake.workspace.CMakeWorkspace
import com.jetbrains.cidr.cpp.execution.CMakeBuildProfileExecutionTarget
import cz.nihil_engine.nihil_utils_plugin.feature_flags.CMakeValues
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Root of the engine codebase (tools, scripts...): the CMake variable NIHIL_ENGINE_DIR of the active profile (then any other),
 * from the profile's `-D` options or its CMakeCache.txt. Falls back to the project dir, which is the engine itself.
 */
object NihilEngineDir {
    const val VARIABLE = "NIHIL_ENGINE_DIR"

    fun of(project: Project): File {
        val base = File(project.basePath.orEmpty())
        val active = activeProfileName(project)
        val infos = profileInfos(project).sortedByDescending { it.profile.name == active }
        val value = infos.firstNotNullOfOrNull { valueOf(it) } ?: return base
        return resolve(base, value)
    }

    /** NIHIL_ENGINE_DIR of every enabled profile that sets it, by profile name; empty when none does. */
    fun byProfile(project: Project): Map<String, File> {
        val base = File(project.basePath.orEmpty())
        return profileInfos(project).mapNotNull { info -> valueOf(info)?.let { info.profile.name to resolve(base, it) } }.toMap()
    }

    /** The CMake profile selected in the run toolbar, or null when the selected target isn't a CMake profile. */
    fun activeProfileName(project: Project): String? =
        runCatching { CMakeBuildProfileExecutionTarget.getProfileName(ExecutionTargetManager.getInstance(project).activeTarget) }.getOrNull()

    private fun profileInfos(project: Project): Collection<CMakeProfileInfo> =
        runCatching { CMakeWorkspace.getInstance(project).profileInfos }.getOrDefault(emptyList())

    private fun valueOf(info: CMakeProfileInfo): String? =
        (CMakeValues.definesFromOptions(CMakeSettings.getOptionsList(info.profile.generationOptions.orEmpty()))[VARIABLE]
            ?: info.generationDir?.let { fromCache(File(it, "CMakeCache.txt")) })?.takeIf { it.isNotBlank() }

    private fun resolve(base: File, value: String): File = File(value).let { if (it.isAbsolute) it else File(base, value) }

    private data class CacheRead(val stamp: Long, val value: String?)

    private val cacheReads = ConcurrentHashMap<String, CacheRead>()

    /** Callers sit on hot paths (highlighting), so a CMakeCache.txt is only re-read when it changes. */
    private fun fromCache(file: File): String? {
        val stamp = file.lastModified() // 0 when missing
        cacheReads[file.path]?.takeIf { it.stamp == stamp }?.let { return it.value }
        val value = if (stamp == 0L) null else runCatching { CMakeValues.parseCache(file.readText())[VARIABLE] }.getOrNull()
        cacheReads[file.path] = CacheRead(stamp, value)
        return value
    }
}
