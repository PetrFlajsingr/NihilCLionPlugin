package cz.nihil_engine.nihil_utils_plugin.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import com.jetbrains.cidr.cpp.cmake.CMakeSettings
import cz.nihil_engine.nihil_utils_plugin.project.BuildTargetsConfig
import cz.nihil_engine.nihil_utils_plugin.project.CVarsConfig
import cz.nihil_engine.nihil_utils_plugin.project.CommitTestsConfig
import cz.nihil_engine.nihil_utils_plugin.project.NihilFeature
import cz.nihil_engine.nihil_utils_plugin.project.NihilProjectConfig
import cz.nihil_engine.nihil_utils_plugin.project.NihilProjectConfigService
import javax.swing.JComponent

/** Settings page for `.idea/nihil_plugin.toml`: Apply creates the file when the project hasn't opted in yet. */
class NihilProjectSettingsConfigurable(private val project: Project) : Configurable {

    private val service get() = NihilProjectConfigService.getInstance(project)

    /** What the form edits; [reload] fills it from the service. */
    private val features = NihilFeature.entries.associateWith { false }.toMutableMap()
    private var cmakeVariable = ""
    private var targets = ""
    private var defaultTarget = ""
    private var profileName = ""
    private var testProfile: String? = null
    private var testTargets = ""
    private var cvarPort = ""
    private var cvarPollMs = ""

    private var dialogPanel: DialogPanel? = null

    override fun getDisplayName(): String = "Nihil Project"

    override fun createComponent(): JComponent {
        reload()
        val file = service.configFile
        val profiles = CMakeSettings.getInstance(project).profiles.map { it.name }
        val problems = service.config.problems
        return panel {
            row {
                comment(
                    if (file.isFile) "Stored in <code>${file.path}</code>. Comments and keys this page doesn't show are kept."
                    else "<code>${file.path}</code> doesn't exist yet: every Nihil feature is off. Apply creates it."
                )
            }
            if (problems.isNotEmpty()) {
                row { comment("Problems in the file: " + problems.joinToString("<br>")) }
            }

            group("Features") {
                for (feature in NihilFeature.entries) {
                    val (title, description) = FEATURE_TEXT.getValue(feature)
                    row {
                        checkBox(title)
                            .bindSelected({ features.getValue(feature) }, { features[feature] = it })
                            .comment(description)
                    }
                }
            }

            group("Build Targets") {
                row("CMake variable:") { textField().bindText(::cmakeVariable).comment("Cache variable a profile sets to pick its target") }
                row("Targets:") { textField().bindText(::targets).align(AlignX.FILL).comment("Comma separated, e.g. Game, Editor, Tools") }
                row("Default target:") { textField().bindText(::defaultTarget).comment("Target of profiles that don't set the variable") }
                row("Profile name:") { textField().bindText(::profileName).comment("Uses {variant} and {target}") }
            }

            group("Commit Tests") {
                row("Test profile:") {
                    comboBox(profiles.ifEmpty { listOf(CommitTestsConfig().profile) })
                        .applyToComponent { isEditable = true }
                        .bindItem(::testProfile)
                        .comment("CMake profile the tests are built and run with")
                }
                row("Target overrides:") {
                    textArea().bindText(::testTargets).rows(4).align(AlignX.FILL)
                        .comment("One library per line, replacing the NihilTest&lt;Name&gt; convention, e.g. <code>RDG = NihilTestRDG_vulkan</code>. Leave the right side empty for a library without tests.")
                }
            }

            group("Console Variables") {
                row("Control port:") {
                    textField().bindText(::cvarPort).columns(6)
                        .comment("Port of the app's console control server, <code>AppConfig::consoleControlPort</code>")
                }
                row("Poll interval (ms):") {
                    textField().bindText(::cvarPollMs).columns(6)
                        .comment("The app doesn't announce value changes, so live values are re-read at this interval while shown")
                }
            }
        }.also { dialogPanel = it }
    }

    override fun isModified(): Boolean = dialogPanel?.isModified() == true

    @Throws(ConfigurationException::class)
    override fun apply() {
        val panel = dialogPanel ?: return
        panel.apply()
        service.save(toConfig())
        reload()
        panel.reset()
    }

    override fun reset() {
        reload()
        dialogPanel?.reset()
    }

    override fun disposeUIResources() {
        dialogPanel = null
    }

    private fun reload() {
        val config = service.config
        NihilFeature.entries.forEach { features[it] = config.isEnabled(it) }
        cmakeVariable = config.buildTargets.cmakeVariable
        targets = config.buildTargets.targets.joinToString(", ")
        defaultTarget = config.buildTargets.defaultTarget
        profileName = config.buildTargets.profileName
        testProfile = config.commitTests.profile
        testTargets = config.commitTests.targets.entries.joinToString("\n") { (library, t) -> "$library = ${t.joinToString(", ")}" }
        cvarPort = config.cvars.port.toString()
        cvarPollMs = config.cvars.pollIntervalMs.toString()
    }

    @Throws(ConfigurationException::class)
    private fun toConfig(): NihilProjectConfig {
        val targetList = splitList(targets)
        if (targetList.isEmpty()) throw ConfigurationException("Build targets can't be empty")
        if (defaultTarget.trim() !in targetList) throw ConfigurationException("Default target \"${defaultTarget.trim()}\" isn't one of the targets")
        if ("{variant}" !in profileName || "{target}" !in profileName) throw ConfigurationException("Profile name must contain {variant} and {target}")
        if (cmakeVariable.isBlank()) throw ConfigurationException("CMake variable can't be empty")
        val profile = testProfile?.trim().orEmpty()
        if (profile.isEmpty()) throw ConfigurationException("Test profile can't be empty")

        val overrides = linkedMapOf<String, List<String>>()
        for ((i, raw) in testTargets.lines().withIndex()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val eq = line.indexOf('=')
            val library = if (eq < 0) "" else line.substring(0, eq).trim()
            if (library.isEmpty() || library.any { it.isWhitespace() }) {
                throw ConfigurationException("Target overrides, line ${i + 1}: expected \"Library = Target, Target\"")
            }
            overrides[library] = splitList(line.substring(eq + 1))
        }

        val port = cvarPort.trim().toIntOrNull()?.takeIf { it in 1..65535 }
            ?: throw ConfigurationException("Console control port must be a number in 1..65535")
        val pollMs = cvarPollMs.trim().toIntOrNull()?.takeIf { it in 250..60_000 }
            ?: throw ConfigurationException("Poll interval must be a number of milliseconds in 250..60000")

        return service.config.copy(
            features = features.filterValues { it }.keys,
            buildTargets = BuildTargetsConfig(cmakeVariable.trim(), targetList, defaultTarget.trim(), profileName.trim()),
            commitTests = CommitTestsConfig(profile, overrides),
            cvars = CVarsConfig(port, pollMs),
        )
    }

    private fun splitList(text: String) = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    companion object {
        private val FEATURE_TEXT = mapOf(
            NihilFeature.BUILD_TARGET_SELECTOR to ("Build target selector" to "Variant and target combos in front of CLion's profile combo"),
            NihilFeature.TOOL_BUTTONS to ("Tool buttons" to "Dashboard, Tracy, RenderDoc and Log Viewer on the run toolbar"),
            NihilFeature.ARGS_POPUP to ("Args popup" to "Target-specific program arguments on the run toolbar"),
            NihilFeature.ASSERT_MENU to ("Assert menu" to "NihilEngine menu for inserting asserts and IDs"),
            NihilFeature.CONSOLE_LINKS to ("Console links" to "Links for assert IDs, files and stack frames in run and debug output"),
            NihilFeature.ASSERT_BREAK_IGNORE to ("Ignore assert breaks" to "\"Ignore\" actions when the debugger stops on a NihilEngine assert"),
            NihilFeature.IGNORED_ASSERTS to ("Ignored asserts" to "Gutter markers and a tool window for asserts in ignored_asserts.txt"),
            NihilFeature.NEW_MODULE to ("New module" to "File | New > Nihil Library, App or Tool from .idea/nihil_templates"),
            NihilFeature.COMMIT_TESTS to ("Commit tests" to "Build and run the tests of libraries a commit touches, and record the result in the message"),
            NihilFeature.COMMIT_ASSERT_IDS to ("Commit assert ID check" to "Block commits that duplicate an assert ID"),
            NihilFeature.FEATURE_FLAGS to ("Feature flags" to "Values of NIHIL_IS_ENABLED flags in every build type, inline and in a matrix tool window"),
            NihilFeature.CVARS to ("Console variables" to "Go to cvar, console links, and live values of a running app over its console control port"),
        )
    }
}
