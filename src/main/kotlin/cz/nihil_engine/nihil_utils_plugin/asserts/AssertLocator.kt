package cz.nihil_engine.nihil_utils_plugin.asserts

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.JList

object AssertLocator {

    data class Hit(val file: VirtualFile, val offset: Int, val line: Int, val snippet: String)

    val SOURCE_EXTENSIONS = setOf(
        "cpp", "cc", "cxx", "c", "h", "hpp", "hxx", "inl", "ipp", "tpp", "ixx", "cppm",
    )

    fun navigateToId(project: Project, id: Long) {
        val label = AssertSite.formatId(id)
        object : Task.Backgroundable(project, "Searching for $label", true) {
            override fun run(indicator: ProgressIndicator) {
                val hits = ReadAction.computeBlocking<List<Hit>, RuntimeException> { find(project, id, indicator) }
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) showResults(project, label, hits)
                }
            }
        }.queue()
    }

    fun find(project: Project, id: Long, indicator: ProgressIndicator?): List<Hit> {
        val pattern = Regex("""(?<![\w])0[xX]0*${java.lang.Long.toHexString(id)}(?![\w])""", RegexOption.IGNORE_CASE)
        val hits = mutableListOf<Hit>()
        ProjectFileIndex.getInstance(project).iterateContent { vf ->
            indicator?.checkCanceled()
            if (vf.isDirectory) return@iterateContent true
            val ext = vf.extension?.lowercase() ?: return@iterateContent true
            if (ext !in SOURCE_EXTENSIONS) return@iterateContent true
            indicator?.text2 = vf.presentableUrl
            val text = try {
                String(vf.contentsToByteArray(), vf.charset)
            } catch (_: Exception) {
                return@iterateContent true
            }
            for (match in pattern.findAll(text)) {
                val found = match.range.first
                val line = text.substring(0, found).count { it == '\n' } + 1
                val lineStart = text.lastIndexOf('\n', found - 1).let { if (it < 0) 0 else it + 1 }
                val lineEnd = text.indexOf('\n', found).let { if (it < 0) text.length else it }
                hits.add(Hit(vf, found, line, text.substring(lineStart, lineEnd).trim()))
            }
            true
        }
        return hits
    }

    data class Located(val file: VirtualFile, val site: AssertSite)

    fun locateAll(project: Project, ids: Set<Long>, indicator: ProgressIndicator?): Map<Long, List<Located>> {
        if (ids.isEmpty()) return emptyMap()
        val macros = AssertMacroService.getInstance(project).macros
        val found = HashMap<Long, MutableList<Located>>()
        ProjectFileIndex.getInstance(project).iterateContent { vf ->
            indicator?.checkCanceled()
            if (vf.isDirectory || vf.extension?.lowercase() !in SOURCE_EXTENSIONS) return@iterateContent true
            val text = try {
                String(vf.contentsToByteArray(), vf.charset)
            } catch (_: Exception) {
                return@iterateContent true
            }
            for (site in AssertSite.findAll(text, macros)) {
                if (site.id in ids) found.getOrPut(site.id) { mutableListOf() } += Located(vf, site)
            }
            true
        }
        return found
    }

    private fun showResults(project: Project, label: String, hits: List<Hit>) {
        when (hits.size) {
            0 -> Messages.showInfoMessage(project, "No occurrences of $label found.", "Go to Assert by ID")
            1 -> navigate(project, hits[0])
            else -> showPicker(project, label, hits)
        }
    }

    private fun navigate(project: Project, hit: Hit) {
        OpenFileDescriptor(project, hit.file, hit.offset).navigate(true)
    }

    private fun showPicker(project: Project, label: String, hits: List<Hit>) {
        val renderer = object : javax.swing.DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): java.awt.Component {
                val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as javax.swing.JLabel
                val hit = value as Hit
                c.text = "${hit.file.name}:${hit.line}    ${hit.snippet}"
                return c
            }
        }

        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(hits)
            .setRenderer(renderer)
            .setTitle("$label — ${hits.size} occurrences")
            .setItemChosenCallback { hit -> navigate(project, hit) }
            .createPopup()
            .showCenteredInCurrentWindow(project)
    }
}
