package cz.nihil_engine.nihil_utils_plugin.feature_flags

import com.intellij.execution.ExecutionTargetManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.messages.Topic
import com.jetbrains.cidr.cpp.cmake.CMakeSettings
import com.jetbrains.cidr.cpp.cmake.CMakeSettingsListener
import com.jetbrains.cidr.cpp.cmake.workspace.CMakeWorkspace
import com.jetbrains.cidr.cpp.cmake.workspace.CMakeWorkspaceListener
import com.jetbrains.cidr.cpp.execution.CMakeBuildProfileExecutionTarget
import cz.nihil_engine.nihil_utils_plugin.project.NihilFeature
import cz.nihil_engine.nihil_utils_plugin.project.NihilProjectConfigService
import java.io.File

/**
 * Builds the [FeatureFlagModel] in the background and keeps it until a config header, cmake/NihilFlags.cmake,
 * a file defining a flag, a CMakeCache.txt or the CMake profiles change.
 */
@Service(Service.Level.PROJECT)
class FeatureFlagModelService(private val project: Project) : Disposable {

    private val log = Logger.getInstance(FeatureFlagModelService::class.java)

    @Volatile
    var model: FeatureFlagModel? = null
        private set

    @Volatile
    var includeCheck: ConfigIncludeCheck? = null
        private set

    @Volatile
    private var building = false

    private val rebuildAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    init {
        project.messageBus.connect(this).apply {
            subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (events.any { affects(it.path) }) invalidate()
                }
            })
            subscribe(CMakeSettingsListener.TOPIC, object : CMakeSettingsListener {
                override fun profilesChanged(old: List<CMakeSettings.Profile>, current: List<CMakeSettings.Profile>) = invalidate()
            })
            subscribe(CMakeWorkspaceListener.TOPIC, object : CMakeWorkspaceListener {
                override fun reloadingFinished(canceled: Boolean) = invalidate()
            })
            subscribe(DumbService.DUMB_MODE, object : DumbService.DumbModeListener {
                override fun exitDumbMode() {
                    if (model == null) request()
                }
            })
            subscribe(NihilProjectConfigService.TOPIC, NihilProjectConfigService.Listener { request() })
        }
    }

    private val basePath: String? get() = project.basePath?.let(FileUtil::toSystemIndependentName)

    private fun affects(rawPath: String): Boolean {
        val path = FileUtil.toSystemIndependentName(rawPath)
        return FeatureFlagModel.configHeader(path) != null ||
            path.endsWith("/$CMAKE_SCRIPT") ||
            path.endsWith("/CMakeCache.txt") ||
            path.endsWith("/config.hpp") ||
            model?.sourceDefinitionPaths?.contains(path) == true
    }

    /** Drops the model and rebuilds it shortly; bursts of changes (a git checkout) coalesce into one rebuild. */
    fun invalidate() {
        if (!isEnabled()) return
        rebuildAlarm.cancelAllRequests()
        rebuildAlarm.addRequest({ build() }, 500)
    }

    /** The model, scheduling a build when there's none yet. Listeners hear about it on [TOPIC]. */
    fun request(): FeatureFlagModel? {
        val current = model
        if (current == null && isEnabled() && !building && rebuildAlarm.isEmpty) invalidate()
        return current
    }

    private fun isEnabled() = NihilProjectConfigService.isEnabled(project, NihilFeature.FEATURE_FLAGS)

    private fun build() {
        if (project.isDisposed || !isEnabled()) return
        building = true
        ReadAction.nonBlocking<Pair<FeatureFlagModel, ConfigIncludeCheck>> { compute() }
            .inSmartMode(project)
            .expireWith(this)
            .coalesceBy(this)
            .finishOnUiThread(ModalityState.any()) { (built, check) ->
                building = false
                model = built
                includeCheck = check
                log.info("Feature flag model: ${built.flags.size} flags, ${built.features.size} Feature constants, ${built.profiles.size} profiles" +
                    (if (built.problems.isEmpty()) "" else "; problems: ${built.problems}"))
                project.messageBus.syncPublisher(TOPIC).modelChanged()
            }
            .submit(AppExecutorUtil.getAppExecutorService())
            .onError {
                building = false
                log.warn("Feature flag model build failed", it)
            }
    }

    private fun compute(): Pair<FeatureFlagModel, ConfigIncludeCheck> {
        val scope = GlobalSearchScope.projectScope(project)
        val base = basePath

        val headerDefines = mutableListOf<Located<FlagDefine>>()
        val headerMacroNames = mutableListOf<Located<String>>()
        val features = mutableListOf<Located<FeatureDecl>>()
        val roots = linkedMapOf<String, VirtualFile>()
        for (bt in BuildType.entries) {
            for (vf in FilenameIndex.getVirtualFilesByName(bt.configFileName, scope)) {
                val path = vf.path
                val (root, _) = FeatureFlagModel.configHeader(path) ?: continue
                vf.parent?.parent?.let { roots[root] = it }
                val text = VfsUtilCore.loadText(vf)
                CppFlagScanner.defines(text).forEach { headerDefines += Located(path, it) }
                CppFlagScanner.allDefineNames(text).forEach { headerMacroNames += Located(path, it) }
                CppFlagScanner.features(text).forEach { features += Located(path, it) }
            }
        }

        val sourceDefines = mutableListOf<Located<FlagDefine>>()
        val index = FileBasedIndex.getInstance()
        for (key in index.getAllKeys(FeatureFlagIndex.NAME, project)) {
            index.processValues(FeatureFlagIndex.NAME, key, null, { file, defines ->
                if (FeatureFlagModel.configHeader(file.path) == null) defines.forEach { sourceDefines += Located(file.path, it) }
                true
            }, scope)
        }

        val cmakeFile = base?.let { LocalFileSystem.getInstance().findFileByPath("$it/$CMAKE_SCRIPT") }
        val cmake = cmakeFile?.let { CMakeFlagScript.parse(VfsUtilCore.loadText(it)) }

        val model = FeatureFlagModel.build(
            defines = headerDefines + sourceDefines,
            features = features,
            cmake = cmake,
            cmakePath = cmakeFile?.path,
            profiles = profileInputs(),
            sourceRoot = base?.let { "$it/src" },
        )

        val dispatchers = roots.mapValues { (_, root) ->
            listOf("Public", "Private/Headers").flatMap { sub ->
                root.findFileByRelativePath(sub)?.children.orEmpty()
                    .filter { it.isDirectory && it.findChild("config.hpp") != null }
                    .map { it.name }
            }
        }
        val (owners, warnings) = ConfigIncludeCheck.owners(dispatchers, headerMacroNames, features)
        warnings.forEach { log.info("Config include check: $it") }
        return model to ConfigIncludeCheck(owners)
    }

    private fun profileInputs(): List<ProfileInputs> {
        val workspace = CMakeWorkspace.getInstance(project)
        val dirs = runCatching { workspace.profileInfos.associate { it.profile.name to it.generationDir } }.getOrDefault(emptyMap())
        return CMakeSettings.getInstance(project).profiles.map { profile ->
            val dir = dirs[profile.name] ?: profile.generationDir?.let { if (it.isAbsolute) it else File(project.basePath ?: "", it.path) }
            val cacheFile = dir?.let { File(it, "CMakeCache.txt") }?.takeIf { it.isFile }
            ProfileInputs(
                name = profile.name,
                enabled = profile.enabled,
                buildType = profile.buildType,
                generationDefines = CMakeValues.definesFromOptions(CMakeSettings.getOptionsList(profile.generationOptions.orEmpty())),
                cache = cacheFile?.let { runCatching { CMakeValues.parseCache(it.readText()) }.getOrNull() },
                cachePath = cacheFile?.path?.let(FileUtil::toSystemIndependentName),
            )
        }
    }

    /** The active CMake profile's name, from the run toolbar's execution target. */
    fun activeProfileName(): String? =
        CMakeBuildProfileExecutionTarget.getProfileName(ExecutionTargetManager.getInstance(project).activeTarget)

    fun navigate(link: SourceLink) {
        val vf = LocalFileSystem.getInstance().findFileByPath(link.path) ?: return
        OpenFileDescriptor(project, vf, link.line, 0).navigate(true)
    }

    override fun dispose() {}

    fun interface Listener {
        fun modelChanged()
    }

    companion object {
        const val CMAKE_SCRIPT = "cmake/NihilFlags.cmake"

        @Topic.ProjectLevel
        val TOPIC = Topic(Listener::class.java)

        fun getInstance(project: Project): FeatureFlagModelService = project.getService(FeatureFlagModelService::class.java)
    }
}
