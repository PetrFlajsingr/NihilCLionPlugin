package cz.nihil_engine.nihil_utils_plugin.commit_checks

import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.coroutineToIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.checkin.CheckinHandlerFactory
import com.intellij.openapi.vcs.checkin.CommitCheck
import com.intellij.openapi.vcs.checkin.CommitInfo
import com.intellij.openapi.vcs.checkin.CommitProblem
import com.intellij.openapi.vcs.checkin.CommitProblemWithDetails
import com.intellij.openapi.vcs.ui.RefreshableOnComponent
import com.intellij.ui.components.JBCheckBox
import cz.nihil_engine.nihil_utils_plugin.project.NihilFeature
import cz.nihil_engine.nihil_utils_plugin.project.NihilProjectConfigService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JComponent

class NihilTestsCheckinHandlerFactory : CheckinHandlerFactory() {
    override fun createHandler(panel: CheckinProjectPanel, commitContext: CommitContext): CheckinHandler =
        NihilTestsCheckinHandler(panel)
}

/**
 * Builds and runs the test executables of every library the commit touches, with the `[commit_tests]` profile,
 * and records the outcome as trailers in the commit message. Skipping is a checkbox that applies to one commit:
 * it clears after every successful commit and isn't saved anywhere.
 */
class NihilTestsCheckinHandler(private val panel: CheckinProjectPanel) : CheckinHandler(), CommitCheck {

    private val project: Project = panel.project
    private var skipBox: JBCheckBox? = null

    override fun getExecutionOrder() = CommitCheck.ExecutionOrder.LATE

    override fun isEnabled() = NihilProjectConfigService.isEnabled(project, NihilFeature.COMMIT_TESTS)

    // Needs the CMake model, not the indexes
    override fun isDumbAware() = true

    override fun getBeforeCheckinConfigurationPanel(): RefreshableOnComponent? {
        if (!isEnabled()) return null
        val box = skipBox ?: JBCheckBox("Skip Nihil tests for this commit").also { skipBox = it }
        return object : RefreshableOnComponent {
            override fun getComponent(): JComponent = box
            override fun saveState() {}
            override fun restoreState() {}
        }
    }

    override fun checkinSuccessful() {
        ApplicationManager.getApplication().invokeLater { skipBox?.isSelected = false }
    }

    override suspend fun runCheck(commitInfo: CommitInfo): CommitProblem? {
        val root = project.basePath?.let(::File) ?: return null
        val config = NihilProjectConfigService.getInstance(project).config.commitTests
        val changed = commitInfo.committedChanges
            .flatMap { listOfNotNull(it.beforeRevision?.file, it.afterRevision?.file) }
            .map { it.ioFile }.distinct()

        val touched = withContext(Dispatchers.IO) { TestSelection.librariesTouched(root, changed) }
        if (touched.libraries.isEmpty() && !touched.allTests) {
            setTrailers(emptyList())
            return null
        }
        val skip = withContext(Dispatchers.EDT) { skipBox?.isSelected == true }

        val (tree, treeProblem) = withContext(Dispatchers.Default) { TestTree.resolve(project, config.profile) }
        if (tree == null) {
            if (skip) {
                setTrailers(touched.libraries.map { "$SKIPPED: Nihil$it" })
                return null
            }
            return TextProblem("Nihil tests: $treeProblem")
        }

        val executables = withContext(Dispatchers.IO) { tree.targets() }
        val selection = withContext(Dispatchers.IO) { TestSelection.select(root, changed, executables.keys, config.targets) }
        if (selection.isEmpty()) {
            setTrailers(emptyList())
            return null
        }
        if (skip) {
            setTrailers(selection.map { "$SKIPPED: ${it.displayName}" })
            return null
        }

        val outcome = withContext(Dispatchers.IO) {
            coroutineToIndicator { indicator -> runTests(tree, selection, executables, indicator) }
        }
        return when (outcome) {
            is Outcome.BuildFailed -> {
                setTrailers(selection.map { "$FAILED: ${it.displayName} (build failed)" })
                OutputProblem(
                    project,
                    "Nihil tests: building ${outcome.targets.joinToString()} failed",
                    "Nihil test build",
                    outcome.output,
                )
            }
            is Outcome.Ran -> {
                val withUncommitted = librariesWithUncommittedChanges(selection, changed)
                setTrailers(outcome.libraries.map { it.trailer(tree.profile, it.library.library in withUncommitted) })
                val failed = outcome.libraries.flatMap { lib -> lib.runs.filter { it.status != TestStatus.PASSED } }
                if (failed.isEmpty()) {
                    null
                } else {
                    OutputProblem(
                        project,
                        "Nihil tests failed: " + failed.joinToString { it.describe() },
                        "Nihil tests",
                        failed.joinToString("\n\n") { "================ ${it.status}: ${it.target} ================\n${it.output.trimEnd()}" },
                    )
                }
            }
        }
    }

    private sealed interface Outcome {
        class BuildFailed(val targets: List<String>, val output: String) : Outcome
        class Ran(val libraries: List<LibraryOutcome>) : Outcome
    }

    private class LibraryOutcome(val library: LibraryTests, val runs: List<TargetRun>, val cachedCases: Int) {
        val passed get() = runs.all { it.status == TestStatus.PASSED }
        val cases get() = cachedCases + runs.sumOf { it.summary?.cases ?: 0 }

        fun trailer(profile: String, uncommitted: Boolean): String {
            val note = if (uncommitted) ", with uncommitted changes" else ""
            return if (passed) "$PASSED: ${library.displayName} ($cases cases, $profile$note)"
            else "$FAILED: ${library.displayName} (${runs.filter { it.status != TestStatus.PASSED }.joinToString { it.describe() }}$note)"
        }
    }

    private fun runTests(tree: TestTree, selection: List<LibraryTests>, executables: Map<String, File>, indicator: ProgressIndicator): Outcome {
        val cache = TestPassCache.getInstance(project)
        indicator.text = "Checking Nihil test inputs"
        val hashes = selection.flatMap { lib -> lib.targets.map { it to InputHash.of(lib.inputs, "${tree.profile}|$it") } }.toMap()
        val cached = hashes.mapNotNull { (target, hash) -> cache.get(target, hash)?.let { target to it } }.toMap()
        val toRun = selection.flatMap { it.targets }.filter { it !in cached }

        val runner = TestRunner(tree, indicator)
        if (toRun.isNotEmpty()) {
            val (exitCode, output) = runner.build(toRun)
            if (exitCode != 0) return Outcome.BuildFailed(toRun, output)
        }
        // Targets named in [commit_tests.targets] that the tree doesn't know have no executable to run
        val runs = toRun.associateWith { target ->
            val exe = executables[target] ?: File(tree.buildDir, target)
            runner.test(target, exe).also { run ->
                if (run.status == TestStatus.PASSED) cache.record(target, TestPassCache.Pass(hashes.getValue(target), run.summary?.cases ?: 0))
            }
        }
        return Outcome.Ran(selection.map { lib ->
            LibraryOutcome(lib, lib.targets.mapNotNull { runs[it] }, lib.targets.sumOf { cached[it]?.cases ?: 0 })
        })
    }

    /** The tests ran on the working tree: say so when it differs from what's committed. */
    private fun librariesWithUncommittedChanges(selection: List<LibraryTests>, committed: List<File>): Set<String> {
        val committedPaths = committed.map { it.canonicalFile }.toSet()
        val local = ChangeListManager.getInstance(project).allChanges
            .flatMap { listOfNotNull(it.beforeRevision?.file, it.afterRevision?.file) }
            .map { it.ioFile.canonicalFile }
            .filter { it !in committedPaths }
        return selection.filter { lib -> local.any { file -> lib.inputs.any { file.startsWith(it) } } }.map { it.library }.toSet()
    }

    private suspend fun setTrailers(lines: List<String>) {
        withContext(Dispatchers.EDT) {
            val current = panel.commitMessage
            val updated = CommitTrailers.apply(current, OWNED_KEYS, lines)
            if (updated != current.trimEnd()) panel.setCommitMessage(updated)
        }
    }

    private class TextProblem(override val text: String) : CommitProblem

    private class OutputProblem(
        private val project: Project,
        override val text: String,
        private val title: String,
        private val output: String,
    ) : CommitProblemWithDetails {
        override val showDetailsAction = "Show Output"

        override fun showDetails(project: Project) = showOutput(this.project, title, output)
    }

    companion object {
        const val PASSED = "Tests-Passed"
        const val FAILED = "Tests-Failed"
        const val SKIPPED = "Tests-Skipped"
        private val OWNED_KEYS = setOf(PASSED, FAILED, SKIPPED)

        private fun TargetRun.describe(): String = when (status) {
            TestStatus.PASSED -> target
            TestStatus.FAILED -> "$target: ${summary?.failed} of ${summary?.cases} cases failed"
            TestStatus.CRASHED -> "$target crashed (exit $exitCode)"
        }

        /** Opens [text] in a Run tool window tab; the Nihil console filters make assert IDs and paths clickable. */
        fun showOutput(project: Project, title: String, text: String) {
            val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
            console.print(text, ConsoleViewContentType.NORMAL_OUTPUT)
            val descriptor = RunContentDescriptor(console, null, console.component, title)
            RunContentManager.getInstance(project).showRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor)
        }
    }
}
