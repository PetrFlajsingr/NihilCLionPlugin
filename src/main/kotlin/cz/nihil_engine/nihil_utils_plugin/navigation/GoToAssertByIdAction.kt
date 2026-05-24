package cz.nihil_engine.nihil_utils_plugin.navigation

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.JList

class GoToAssertByIdAction : AnAction("Go to Assert by ID...", "Find and navigate to an assert by its hex ID", null), DumbAware {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val raw = Messages.showInputDialog(
            project,
            "Hex ID (with or without 0x prefix):",
            "Go to Assert by ID",
            Messages.getQuestionIcon(),
            "",
            HexInputValidator,
        ) ?: return

        val needle = normalize(raw) ?: run {
            Messages.showWarningDialog(project, "Not a valid hex ID: $raw", "Go to Assert by ID")
            return
        }

        runSearch(project, needle)
    }

    private fun runSearch(project: Project, needle: String) {
        object : Task.Backgroundable(project, "Searching for $needle", true) {
            override fun run(indicator: ProgressIndicator) {
                val hits = ReadAction.compute<List<Hit>, RuntimeException> {
                    findOccurrences(project, needle, indicator)
                }
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    showResults(project, needle, hits)
                }
            }
        }.queue()
    }

    private fun findOccurrences(project: Project, needle: String, indicator: ProgressIndicator): List<Hit> {
        val hits = mutableListOf<Hit>()
        val fileIndex = ProjectFileIndex.getInstance(project)
        fileIndex.iterateContent { vf ->
            indicator.checkCanceled()
            if (vf.isDirectory) return@iterateContent true
            val ext = vf.extension?.lowercase() ?: return@iterateContent true
            if (ext !in SOURCE_EXTENSIONS) return@iterateContent true
            indicator.text2 = vf.presentableUrl
            val text = try {
                String(vf.contentsToByteArray(), vf.charset)
            } catch (_: Exception) {
                return@iterateContent true
            }
            var idx = 0
            while (true) {
                val found = text.indexOf(needle, idx, ignoreCase = true)
                if (found < 0) break
                if (isWholeHexLiteral(text, found, needle.length)) {
                    val line = text.substring(0, found).count { it == '\n' } + 1
                    val lineStart = text.lastIndexOf('\n', found - 1).let { if (it < 0) 0 else it + 1 }
                    val lineEnd = text.indexOf('\n', found).let { if (it < 0) text.length else it }
                    val snippet = text.substring(lineStart, lineEnd).trim()
                    hits.add(Hit(vf, found, line, snippet))
                }
                idx = found + needle.length
            }
            true
        }
        return hits
    }

    private fun isWholeHexLiteral(text: String, start: Int, length: Int): Boolean {
        if (start > 0) {
            val before = text[start - 1]
            if (before.isLetterOrDigit() || before == '_') return false
        }
        val endIdx = start + length
        if (endIdx < text.length) {
            val after = text[endIdx]
            if (after.isLetterOrDigit() || after == '_') return false
        }
        return true
    }

    private fun showResults(project: Project, needle: String, hits: List<Hit>) {
        when (hits.size) {
            0 -> Messages.showInfoMessage(project, "No occurrences of $needle found.", "Go to Assert by ID")
            1 -> navigate(project, hits[0])
            else -> showPicker(project, needle, hits)
        }
    }

    private fun navigate(project: Project, hit: Hit) {
        OpenFileDescriptor(project, hit.file, hit.offset).navigate(true)
    }

    private fun showPicker(project: Project, needle: String, hits: List<Hit>) {
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
            .setTitle("$needle — ${hits.size} occurrences")
            .setItemChosenCallback { hit -> navigate(project, hit) }
            .createPopup()
            .showCenteredInCurrentWindow(project)
    }

    private fun normalize(raw: String): String? {
        val trimmed = raw.trim().removePrefix("0x").removePrefix("0X")
        if (trimmed.isEmpty()) return null
        if (!trimmed.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return "0x" + trimmed.uppercase()
    }

    private data class Hit(val file: VirtualFile, val offset: Int, val line: Int, val snippet: String)

    companion object {
        private val SOURCE_EXTENSIONS = setOf(
            "cpp", "cc", "cxx", "c", "h", "hpp", "hxx", "inl", "ipp", "tpp",
        )
    }

    private object HexInputValidator : com.intellij.openapi.ui.InputValidator {
        override fun checkInput(input: String?): Boolean {
            if (input.isNullOrBlank()) return false
            val s = input.trim().removePrefix("0x").removePrefix("0X")
            return s.isNotEmpty() && s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        }
        override fun canClose(input: String?): Boolean = checkInput(input)
    }
}
