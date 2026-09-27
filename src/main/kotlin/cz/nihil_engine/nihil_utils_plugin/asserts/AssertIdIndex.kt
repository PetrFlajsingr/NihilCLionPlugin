package cz.nihil_engine.nihil_utils_plugin.asserts

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor
import java.io.DataInput
import java.io.DataOutput

/**
 * Assert ID -> the macros that use it, per source file, built while the IDE indexes the project and kept up
 * to date as files change.
 *
 * The index can't depend on the project's assert_names.txt (it's shared by every project and only rebuilt when
 * [getVersion] changes), so it records every `UPPER_CASE_MACRO(0x...` call and callers filter by
 * [AssertMacros] when they query it.
 */
class AssertIdIndex : FileBasedIndexExtension<String, List<String>>() {

    override fun getName(): ID<String, List<String>> = NAME

    override fun getIndexer(): DataIndexer<String, List<String>, FileContent> = DataIndexer { content ->
        val found = HashMap<String, MutableSet<String>>()
        for (m in CALL.findAll(content.contentAsText)) {
            val id = AssertSite.parseId(m.groupValues[2]) ?: continue
            found.getOrPut(key(id)) { sortedSetOf() } += m.groupValues[1]
        }
        found.mapValues { it.value.toList() }
    }

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getValueExternalizer(): DataExternalizer<List<String>> = MacroListExternalizer

    override fun getVersion() = 1

    override fun getInputFilter(): FileBasedIndex.InputFilter =
        FileBasedIndex.InputFilter { it.extension?.lowercase() in AssertLocator.SOURCE_EXTENSIONS }

    override fun dependsOnFileContent() = true

    private object MacroListExternalizer : DataExternalizer<List<String>> {
        override fun save(out: DataOutput, value: List<String>) {
            out.writeInt(value.size)
            value.forEach(out::writeUTF)
        }

        override fun read(input: DataInput): List<String> = List(input.readInt()) { input.readUTF() }
    }

    companion object {
        val NAME: ID<String, List<String>> = ID.create("cz.nihil_engine.nihil_utils_plugin.AssertIdIndex")

        private val CALL = Regex("""\b([A-Z][A-Z0-9_]*)\s*\(\s*0[xX]([0-9A-Fa-f]+)""")

        private fun key(id: Long) = AssertSite.formatId(id)

        /** Project files that use [id] with one of [macros]. Needs a read action in smart mode. */
        fun filesWith(project: Project, id: Long, macros: AssertMacros): List<VirtualFile> {
            val names = macros.names.toSet()
            val files = mutableListOf<VirtualFile>()
            FileBasedIndex.getInstance().processValues(NAME, key(id), null, { file, used ->
                if (used.any { it in names }) files += file
                true
            }, GlobalSearchScope.projectScope(project))
            return files
        }
    }
}
