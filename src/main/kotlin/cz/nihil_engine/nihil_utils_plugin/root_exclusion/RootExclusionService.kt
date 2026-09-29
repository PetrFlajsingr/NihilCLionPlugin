package cz.nihil_engine.nihil_utils_plugin.root_exclusion

import com.intellij.execution.ExecutionTargetListener
import com.intellij.execution.ExecutionTargetManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.Alarm
import com.jetbrains.cidr.cpp.cmake.CMakeSettings
import com.jetbrains.cidr.cpp.cmake.CMakeSettingsListener
import com.jetbrains.cidr.cpp.cmake.workspace.CMakeWorkspace
import com.jetbrains.cidr.cpp.cmake.workspace.CMakeWorkspaceListener
import com.jetbrains.cidr.project.CidrRootConfiguration
import cz.nihil_engine.nihil_utils_plugin.project.NihilEngineDir
import cz.nihil_engine.nihil_utils_plugin.project.NihilFeature
import cz.nihil_engine.nihil_utils_plugin.project.NihilProjectConfigService
import java.io.File

/**
 * Keeps `engine/` excluded while the selected profile builds against a linked engine checkout, and the generation dirs of
 * disabled profiles excluded, through the same root configuration as "Mark Directory as > Excluded" (CLion rewrites
 * .idea/misc.xml from it). Re-evaluated after every CMake reload, profile change and profile selection.
 *
 * The exclusions it made are remembered in the workspace file, so it only ever removes its own.
 */
@Service(Service.Level.PROJECT)
@State(name = "NihilRootExclusion", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class RootExclusionService(private val project: Project) :
    SimplePersistentStateComponent<RootExclusionService.OwnedRoots>(OwnedRoots()), Disposable {

    class OwnedRoots : BaseState() {
        var paths by list<String>()
    }

    private val log = Logger.getInstance(RootExclusionService::class.java)

    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    /** The last engine dir disagreement logged, so every reload doesn't repeat it. */
    @Volatile
    private var loggedDisagreement: String? = null

    init {
        project.messageBus.connect(this).apply {
            subscribe(CMakeWorkspaceListener.TOPIC, object : CMakeWorkspaceListener {
                override fun reloadingFinished(canceled: Boolean) = update()
            })
            subscribe(CMakeSettingsListener.TOPIC, object : CMakeSettingsListener {
                override fun profilesChanged(old: List<CMakeSettings.Profile>, current: List<CMakeSettings.Profile>) = update()
            })
            subscribe(ExecutionTargetManager.TOPIC, ExecutionTargetListener { update() })
            subscribe(NihilProjectConfigService.TOPIC, NihilProjectConfigService.Listener { update() })
        }
    }

    /** Re-evaluates shortly; bursts of events (a reload also changes the execution targets) coalesce into one pass. */
    fun update() {
        if (project.isDisposed) return
        alarm.cancelAllRequests()
        alarm.addRequest({ evaluate() }, 300)
    }

    private fun evaluate() {
        if (project.isDisposed) return
        val enabled = NihilProjectConfigService.isEnabled(project, NihilFeature.ENGINE_ROOT_EXCLUSION)
        // Turned off: undo whatever the plugin excluded. Nothing to undo: leave CLion's configuration untouched.
        if (!enabled && state.paths.isEmpty()) return
        val wanted = if (enabled) ReadAction.compute<RootExclusionPlan.Wanted, Throwable> { wanted() } else RootExclusionPlan.Wanted(emptyList())
        ApplicationManager.getApplication().invokeLater({ apply(wanted) }, project.disposed)
    }

    private fun wanted(): RootExclusionPlan.Wanted {
        val base = project.basePath?.let(::normalize) ?: return RootExclusionPlan.Wanted(emptyList())

        val byProfile = NihilEngineDir.byProfile(project).mapValues { normalize(it.value.path) }
        val active = NihilEngineDir.activeProfileName(project)
        val engineDir = byProfile[active] ?: byProfile.values.firstOrNull()
        val distinct = byProfile.values.distinctBy { if (SystemInfo.isFileSystemCaseSensitive) it else it.lowercase() }
        if (distinct.size > 1) {
            val message = "Enabled CMake profiles disagree about ${NihilEngineDir.VARIABLE} ($byProfile); going by the selected profile $active: $engineDir"
            if (message != loggedDisagreement) log.warn(message)
            loggedDisagreement = message
        }

        val profiles = CMakeSettings.getInstance(project).profiles
        val generationDirs = runCatching { CMakeSettings.getEffectiveProfileGenerationDirs(project, profiles) }.getOrNull()
            ?.takeIf { it.size == profiles.size }
            ?: profiles.map { p -> p.generationDir?.let { if (it.isAbsolute) it else File(base, it.path) } }
        val (enabledDirs, disabledDirs) = profiles.zip(generationDirs)
            .mapNotNull { (profile, dir) -> dir?.let { profile.enabled to normalize(it.path) } }
            .partition { it.first }
        val infoDirs = runCatching { CMakeWorkspace.getInstance(project).profileInfos.mapNotNull { it.generationDir?.path?.let(::normalize) } }
            .getOrDefault(emptyList())

        return RootExclusionPlan.wanted(
            base = base,
            engineDir = engineDir,
            engineRootExists = File(base, RootExclusionPlan.ENGINE_SUBDIR).isDirectory,
            disabledGenerationDirs = disabledDirs.map { it.second }.filter { File(it).isDirectory },
            enabledGenerationDirs = enabledDirs.map { it.second } + infoDirs,
        )
    }

    private fun apply(wanted: RootExclusionPlan.Wanted) {
        val roots = CidrRootConfiguration.getInstance(project)
        val changes = RootExclusionPlan.changes(wanted, roots.getExcludeRoots().map { normalize(it.path) }, state.paths)
        if (changes.owned != state.paths) state.paths = changes.owned.toMutableList()
        if (changes.add.isEmpty() && changes.remove.isEmpty()) return

        log.info("Root exclusion: excluding ${changes.add}, un-excluding ${changes.remove}")
        WriteAction.run<Throwable> {
            roots.mergeConfigurationChangesDuring {
                changes.remove.forEach { roots.removeRootBlocking(File(it)) }
                changes.add.forEach { roots.addExcludeRootBlocking(File(it)) }
            }
        }
    }

    private fun normalize(path: String): String = FileUtil.toSystemIndependentName(FileUtil.toCanonicalPath(path)).trimEnd('/')

    override fun dispose() {}

    companion object {
        fun getInstance(project: Project): RootExclusionService = project.getService(RootExclusionService::class.java)
    }
}
