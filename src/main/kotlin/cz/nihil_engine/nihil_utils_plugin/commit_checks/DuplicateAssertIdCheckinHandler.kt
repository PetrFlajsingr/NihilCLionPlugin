package cz.nihil_engine.nihil_utils_plugin.commit_checks

import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.checkin.CheckinHandlerFactory
import com.intellij.openapi.vcs.checkin.CommitCheck
import com.intellij.openapi.vcs.checkin.CommitInfo
import com.intellij.openapi.vcs.checkin.CommitProblem
import com.intellij.openapi.vcs.checkin.CommitProblemWithDetails
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.SimpleListCellRenderer
import cz.nihil_engine.nihil_utils_plugin.asserts.AssertIdIndex
import cz.nihil_engine.nihil_utils_plugin.asserts.AssertLocator
import cz.nihil_engine.nihil_utils_plugin.asserts.AssertMacroService
import cz.nihil_engine.nihil_utils_plugin.asserts.AssertMacros
import cz.nihil_engine.nihil_utils_plugin.asserts.AssertSite
import cz.nihil_engine.nihil_utils_plugin.project.NihilFeature
import cz.nihil_engine.nihil_utils_plugin.project.NihilProjectConfigService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DuplicateAssertIdCheckinHandlerFactory : CheckinHandlerFactory() {
    override fun createHandler(panel: CheckinProjectPanel, commitContext: CommitContext): CheckinHandler =
        DuplicateAssertIdCheckinHandler(panel)
}

/**
 * Blocks a commit whose files use an assert ID that another assert already uses, and records the outcome as a trailer
 * in the commit message. Only the committed files are parsed: every other file is looked up in [AssertIdIndex].
 */
class DuplicateAssertIdCheckinHandler(private val panel: CheckinProjectPanel) : CheckinHandler(), CommitCheck {

    private val project: Project = panel.project

    override fun getExecutionOrder() = CommitCheck.ExecutionOrder.EARLY

    override fun isEnabled() = NihilProjectConfigService.isEnabled(project, NihilFeature.COMMIT_ASSERT_IDS)

    // Waits for smart mode itself, in runCheck
    override fun isDumbAware() = true

    data class Occurrence(val file: VirtualFile?, val path: String, val line: Int) {
        override fun toString() = "${path.substringAfterLast('/')}:${line + 1}"
    }

    override suspend fun runCheck(commitInfo: CommitInfo): CommitProblem? {
        val macros = AssertMacroService.getInstance(project).macros
        // Committed content, which for a partially committed file is what the commit will hold
        val committed = withContext(Dispatchers.IO) {
            commitInfo.committedChanges.mapNotNull { change ->
                val revision = change.afterRevision ?: return@mapNotNull null
                if (revision.file.name.substringAfterLast('.', "").lowercase() !in AssertLocator.SOURCE_EXTENSIONS) return@mapNotNull null
                val text = revision.content ?: return@mapNotNull null
                revision.file to AssertSite.findAll(text, macros)
            }
        }
        val deletedOrCommitted = commitInfo.committedChanges
            .flatMap { listOfNotNull(it.beforeRevision?.file?.path, it.afterRevision?.file?.path) }.toSet()

        val occurrences = HashMap<Long, MutableList<Occurrence>>()
        for ((file, sites) in committed) {
            for ((_, _, id, line) in sites) occurrences.getOrPut(id) { mutableListOf() } += Occurrence(file.virtualFile, file.path,
                line
            )
        }
        if (occurrences.isEmpty()) {
            panel.setTrailers(OWNED_KEYS, emptyList())
            return null
        }

        val elsewhere = smartReadAction(project) {
            occurrences.keys.associateWith { id ->
                AssertIdIndex.filesWith(project, id, macros).filter { it.path !in deletedOrCommitted }
            }
        }
        for ((id, files) in elsewhere) {
            if (files.isEmpty()) continue
            val located = withContext(Dispatchers.IO) { files.flatMap { locate(it, id, macros) } }
            occurrences.getValue(id) += located
        }

        val duplicates = occurrences.filterValues { it.size > 1 }.toSortedMap()
        // A blocked commit can still go ahead with "Commit Anyway": the duplicate trailer records that
        panel.setTrailers(OWNED_KEYS, trailers(occurrences.size, duplicates.keys))
        if (duplicates.isEmpty()) return null
        return DuplicatesProblem(project, duplicates)
    }

    private fun locate(file: VirtualFile, id: Long, macros: AssertMacros): List<Occurrence> {
        val text = try {
            String(file.contentsToByteArray(), file.charset)
        } catch (_: Exception) {
            return listOf(Occurrence(file, file.path, 0))
        }
        return AssertSite.findAll(text, macros).filter { it.id == id }.map { Occurrence(file, file.path, it.line) }
    }

    private class DuplicatesProblem(private val project: Project, private val duplicates: Map<Long, List<Occurrence>>) : CommitProblemWithDetails {

        override val text: String
            get() {
                val shown = duplicates.entries.take(MAX_LISTED).joinToString("; ") { (id, occ) -> "${AssertSite.formatId(id)} (${occ.joinToString()})" }
                val more = if (duplicates.size > MAX_LISTED) " and ${duplicates.size - MAX_LISTED} more" else ""
                return "Duplicate assert IDs: $shown$more"
            }

        override val showDetailsAction = "Show Duplicates"

        override fun showDetails(project: Project) {
            val rows = duplicates.flatMap { (id, occ) -> occ.map { AssertSite.formatId(id) to it } }
            JBPopupFactory.getInstance()
                .createPopupChooserBuilder(rows)
                .setRenderer(SimpleListCellRenderer.create { label, (id, occ), _ -> label.text = "$id    $occ    ${occ.path}" })
                .setTitle("Duplicate Assert IDs")
                .setItemChosenCallback { (_, occ) -> occ.file?.let { OpenFileDescriptor(this.project, it, occ.line, 0).navigate(true) } }
                .createPopup()
                .showCenteredInCurrentWindow(this.project)
        }

        companion object {
            private const val MAX_LISTED = 5
        }
    }

    companion object {
        const val UNIQUE = "Assert-IDs-Unique"
        const val DUPLICATE = "Assert-IDs-Duplicate"
        private val OWNED_KEYS = setOf(UNIQUE, DUPLICATE)
        private const val MAX_TRAILER_IDS = 5

        /** [checked] is how many IDs the committed files use; [duplicates] the ones that some other assert uses too. */
        fun trailers(checked: Int, duplicates: Collection<Long>): List<String> {
            if (duplicates.isEmpty()) return listOf("$UNIQUE: $checked checked")
            val sorted = duplicates.sorted()
            val more = if (sorted.size > MAX_TRAILER_IDS) " and ${sorted.size - MAX_TRAILER_IDS} more" else ""
            return listOf("$DUPLICATE: ${sorted.take(MAX_TRAILER_IDS).joinToString { AssertSite.formatId(it) }}$more")
        }
    }
}
