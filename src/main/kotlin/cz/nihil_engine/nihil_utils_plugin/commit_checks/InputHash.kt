package cz.nihil_engine.nihil_utils_plugin.commit_checks

import java.io.File
import java.security.MessageDigest

/** Content hash of a set of files and directories: equal hashes mean a cached test pass still holds. */
object InputHash {

    fun of(inputs: Collection<File>, extra: String = ""): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(extra.toByteArray())
        for (input in inputs.sortedBy { it.path }) {
            val files = if (input.isDirectory) {
                input.walkTopDown().onEnter { !it.name.startsWith(".") }.filter { it.isFile }.sortedBy { it.path }.toList()
            } else {
                listOf(input).filter { it.isFile }
            }
            for (file in files) {
                digest.update(file.path.replace('\\', '/').toByteArray())
                digest.update(0)
                digest.update(file.readBytes())
                digest.update(0)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
