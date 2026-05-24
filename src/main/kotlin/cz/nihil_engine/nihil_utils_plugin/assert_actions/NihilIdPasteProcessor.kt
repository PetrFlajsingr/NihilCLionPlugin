package cz.nihil_engine.nihil_utils_plugin.assert_actions

import com.intellij.codeInsight.editorActions.CopyPastePreProcessor
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RawText
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import kotlin.random.Random

class NihilIdPasteProcessor : CopyPastePreProcessor {

    override fun preprocessOnCopy(
        file: PsiFile,
        startOffsets: IntArray,
        endOffsets: IntArray,
        text: String,
    ): String? = null

    override fun preprocessOnPaste(
        project: Project,
        file: PsiFile,
        editor: Editor,
        text: String,
        rawText: RawText?,
    ): String {
        if (!MACRO_CALL.containsMatchIn(text)) return text
        return MACRO_CALL.replace(text) { match ->
            val prefix = match.groupValues[1]
            val hex = match.groupValues[2]
            val width = hex.length - 2 // strip "0x"
            "${prefix}0x${randomHex(width)}"
        }
    }

    private fun randomHex(width: Int): String =
        buildString(width) {
            repeat(width) { append(HEX_CHARS[Random.nextInt(16)]) }
        }

    companion object {
        private const val HEX_CHARS = "0123456789ABCDEF"
    }
}

private val MACRO_CALL = Regex("(NIHIL_[A-Z0-9_]+\\s*\\(\\s*)(0x[0-9A-Fa-f]+)")