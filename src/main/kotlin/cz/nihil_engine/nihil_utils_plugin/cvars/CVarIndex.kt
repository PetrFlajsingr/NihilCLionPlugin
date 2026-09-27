package cz.nihil_engine.nihil_utils_plugin.cvars

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
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
import cz.nihil_engine.nihil_utils_plugin.asserts.AssertLocator
import java.io.DataInput
import java.io.DataOutput

/** One indexed declaration of a name in a file. */
data class CVarIndexEntry(val kind: CVarKind, val nameOffset: Int, val help: String?)

/**
 * Console object name (or annotation prefix) -> its declarations in a file, from [CVarScanner]. A name can
 * legitimately be declared by several apps (`test.debug_mode` in the RDG_MatSys test apps), so nothing here
 * treats repeats as a problem.
 */
class CVarIndex : FileBasedIndexExtension<String, List<CVarIndexEntry>>() {

    override fun getName(): ID<String, List<CVarIndexEntry>> = NAME

    override fun getIndexer(): DataIndexer<String, List<CVarIndexEntry>, FileContent> = DataIndexer { content ->
        CVarScanner.scan(content.contentAsText)
            .groupBy({ it.name }, { CVarIndexEntry(it.kind, it.nameOffset, it.help) })
    }

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getValueExternalizer(): DataExternalizer<List<CVarIndexEntry>> = EntryListExternalizer

    override fun getVersion() = 1

    override fun getInputFilter(): FileBasedIndex.InputFilter =
        FileBasedIndex.InputFilter { it.extension?.lowercase() in AssertLocator.SOURCE_EXTENSIONS }

    override fun dependsOnFileContent() = true

    private object EntryListExternalizer : DataExternalizer<List<CVarIndexEntry>> {
        override fun save(out: DataOutput, value: List<CVarIndexEntry>) {
            out.writeInt(value.size)
            for (e in value) {
                out.writeByte(e.kind.ordinal)
                out.writeInt(e.nameOffset)
                out.writeBoolean(e.help != null)
                e.help?.let(out::writeUTF)
            }
        }

        override fun read(input: DataInput): List<CVarIndexEntry> = List(input.readInt()) {
            val kind = CVarKind.entries[input.readByte().toInt()]
            val offset = input.readInt()
            CVarIndexEntry(kind, offset, if (input.readBoolean()) input.readUTF() else null)
        }
    }

    companion object {
        val NAME: ID<String, List<CVarIndexEntry>> = ID.create("cz.nihil_engine.nihil_utils_plugin.CVarIndex")
    }
}

/** A declaration found through [CVarIndex]. [viaPrefix]: the name comes from a `$cvar{"prefix"}` struct. */
data class CVarLocation(val name: String, val file: VirtualFile, val entry: CVarIndexEntry, val viaPrefix: Boolean)

object CVarLocator {

    /**
     * Declarations of [name]: exact matches, else the annotated structs whose prefix it starts with (longest
     * prefix first). Needs a read action in smart mode.
     */
    fun find(project: Project, name: String): List<CVarLocation> {
        val exact = lookup(project, name, prefix = false)
        if (exact.isNotEmpty()) return exact
        var candidate = name
        while ('.' in candidate) {
            candidate = candidate.substringBeforeLast('.')
            val found = lookup(project, candidate, prefix = true)
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    private fun lookup(project: Project, key: String, prefix: Boolean): List<CVarLocation> {
        val result = mutableListOf<CVarLocation>()
        FileBasedIndex.getInstance().processValues(CVarIndex.NAME, key, null, { file, entries ->
            for (e in entries) if ((e.kind == CVarKind.PREFIX) == prefix) result += CVarLocation(key, file, e, prefix)
            true
        }, GlobalSearchScope.projectScope(project))
        return result.sortedWith(compareBy({ it.file.path }, { it.entry.nameOffset }))
    }

    /** Every indexed name with its kind and help text; one entry per name. Needs a read action in smart mode. */
    fun allNames(project: Project): Map<String, CVarIndexEntry> {
        val index = FileBasedIndex.getInstance()
        val scope = GlobalSearchScope.projectScope(project)
        val result = sortedMapOf<String, CVarIndexEntry>()
        for (key in index.getAllKeys(CVarIndex.NAME, project)) {
            val first = index.getValues(CVarIndex.NAME, key, scope).firstOrNull()?.firstOrNull() ?: continue
            result[key] = first
        }
        return result
    }

    /** [allNames] without blocking: empty while indexing. */
    fun allNamesOrEmpty(project: Project): Map<String, CVarIndexEntry> =
        if (DumbService.isDumb(project)) emptyMap() else ReadAction.compute<Map<String, CVarIndexEntry>, RuntimeException> { allNames(project) }
}
