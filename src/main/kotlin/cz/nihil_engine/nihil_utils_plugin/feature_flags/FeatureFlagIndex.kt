package cz.nihil_engine.nihil_utils_plugin.feature_flags

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

/**
 * Flag macro -> its `#define`s in a file: `#define NIHIL_X NIHIL_ENABLED` and friends anywhere in the sources,
 * so flags defined outside config headers (NihilMacros/reflection.hpp, a `.cpp` defining its own logging switch)
 * are found without scanning every file when the model is rebuilt. Config headers are indexed too but read
 * directly by [FeatureFlagModelService], which needs more from them.
 */
class FeatureFlagIndex : FileBasedIndexExtension<String, List<FlagDefine>>() {

    override fun getName(): ID<String, List<FlagDefine>> = NAME

    override fun getIndexer(): DataIndexer<String, List<FlagDefine>, FileContent> = DataIndexer { content ->
        val text = content.contentAsText
        if (!CppFlagScanner.mayDeclare(text)) return@DataIndexer emptyMap()
        CppFlagScanner.defines(text)
            .filter { it.value != DefineValue.OTHER || it.rawValue.startsWith("NIHIL_") }
            .groupBy { it.name }
    }

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getValueExternalizer(): DataExternalizer<List<FlagDefine>> = DefineListExternalizer

    override fun getVersion() = 1

    override fun getInputFilter(): FileBasedIndex.InputFilter =
        FileBasedIndex.InputFilter { it.extension?.lowercase() in AssertLocator.SOURCE_EXTENSIONS }

    override fun dependsOnFileContent() = true

    private object DefineListExternalizer : DataExternalizer<List<FlagDefine>> {
        override fun save(out: DataOutput, value: List<FlagDefine>) {
            out.writeInt(value.size)
            for (d in value) {
                out.writeUTF(d.name)
                out.writeByte(d.value.ordinal)
                out.writeUTF(d.rawValue)
                out.writeInt(d.line)
                out.writeBoolean(d.guarded)
                out.writeInt(d.conditions.size)
                for (c in d.conditions) {
                    out.writeUTF(c.directive)
                    out.writeUTF(c.expression)
                }
            }
        }

        override fun read(input: DataInput): List<FlagDefine> = List(input.readInt()) {
            FlagDefine(
                name = input.readUTF(),
                value = DefineValue.entries[input.readByte().toInt()],
                rawValue = input.readUTF(),
                line = input.readInt(),
                guarded = input.readBoolean(),
                conditions = List(input.readInt()) { PpCondition(input.readUTF(), input.readUTF()) },
            )
        }
    }

    companion object {
        val NAME: ID<String, List<FlagDefine>> = ID.create("cz.nihil_engine.nihil_utils_plugin.FeatureFlagIndex")
    }
}
