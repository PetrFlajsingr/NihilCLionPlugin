package cz.nihil_engine.nihil_utils_plugin.asserts

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import cz.nihil_engine.nihil_utils_plugin.project.NihilEngineDir
import java.io.File

enum class AssertKind(val macro: String, val typeName: String, val fatal: Boolean) {
    ENSURE_PARANOID("NIHIL_ENSURE_PARANOID", "ParanoidEnsure", false),
    ASSERT_PARANOID("NIHIL_ASSERT_PARANOID", "ParanoidAssert", true),
    ENSURE("NIHIL_ENSURE", "Ensure", false),
    ASSERT("NIHIL_ASSERT", "Assert", true),
    VERIFY("NIHIL_VERIFY", "Verify", true),
    DBG_WARN("NIHIL_DBG_WARN", "DbgWarning", false),
    DBG_ERROR("NIHIL_DBG_ERROR", "DbgError", true),
    WARN("NIHIL_WARN", "Warning", false),
    ERROR("NIHIL_ERROR", "Error", true);

    companion object {
        private val byMacro = entries.associateBy { it.macro }
        val TYPE_NAMES: List<String> = entries.map { it.typeName }

        fun of(macro: String): AssertKind {
            byMacro[macro]?.let { return it }
            val words = macro.split('_').toSet()
            val paranoid = "PARANOID" in words
            return when {
                "VERIFY" in words -> VERIFY
                "ENSURE" in words -> if (paranoid) ENSURE_PARANOID else ENSURE
                else -> if (paranoid) ASSERT_PARANOID else ASSERT
            }
        }
    }
}

class AssertMacros(names: Collection<String>) {
    val names: List<String> = names.distinct()

    internal val invocation = Regex(
        """\b(${this.names.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }})\s*\(\s*0[xX]([0-9A-Fa-f]+)"""
    )

    companion object {
        val CORE = AssertMacros(AssertKind.entries.map { it.macro })

        fun parse(text: String): AssertMacros? =
            text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
                .takeIf { it.isNotEmpty() }?.let(::AssertMacros)
    }
}

@Service(Service.Level.PROJECT)
class AssertMacroService(private val project: Project) {

    private val log = Logger.getInstance(AssertMacroService::class.java)

    private data class Cached(val stamp: Long, val macros: AssertMacros)

    @Volatile
    private var cached: Cached? = null

    val macros: AssertMacros
        get() {
            val file = File(NihilEngineDir.of(project), NAMES_FILE)
            val stamp = file.lastModified() // 0 when missing
            cached?.takeIf { it.stamp == stamp }?.let { return it.macros }
            val macros = load(file)
            cached = Cached(stamp, macros)
            return macros
        }

    private fun load(file: File): AssertMacros {
        if (!file.isFile) {
            log.info("$NAMES_FILE not found, using the core assert macros")
            return AssertMacros.CORE
        }
        return try {
            AssertMacros.parse(file.readText()) ?: AssertMacros.CORE.also { log.warn("$NAMES_FILE is empty, using the core assert macros") }
        } catch (e: Exception) {
            log.warn("Failed to read $NAMES_FILE, using the core assert macros", e)
            AssertMacros.CORE
        }
    }

    companion object {
        const val NAMES_FILE = "tools/scripts/assert_names.txt"

        fun getInstance(project: Project): AssertMacroService = project.getService(AssertMacroService::class.java)
    }
}
