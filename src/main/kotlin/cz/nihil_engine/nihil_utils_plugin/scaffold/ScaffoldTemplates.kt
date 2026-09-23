package cz.nihil_engine.nihil_utils_plugin.scaffold

import com.intellij.openapi.project.Project
import cz.nihil_engine.nihil_utils_plugin.util.SimpleToml
import java.io.File
import java.net.URI
import java.net.URL
import java.util.jar.JarFile

enum class ScaffoldKind(val id: String) {
    LIBRARY("library"),
    INTERFACE_LIBRARY("interface_library"),
    APP("app"),
    TOOL("tool"),
    /** Created together with a library, when asked for. */
    LIBRARY_TEST("library_test"),
}

/**
 * One template: where its files go and how the new directory is registered, all with placeholders (see
 * [TemplateRenderer]). [files] maps a relative path template to its content template.
 */
data class ScaffoldTemplate(
    val kind: ScaffoldKind,
    val label: String,
    val description: String,
    val targetDir: String,
    /** Relative to the project root. */
    val registerFile: String,
    val registerSection: String?,
    val registerLine: String,
    /** Rendered paths, relative to the target directory, opened after creation. */
    val open: List<String>,
    val files: Map<String, String>,
    /** Where it came from, for messages: the project's template directory or "bundled". */
    val origin: String,
)

/**
 * Templates live in the project, under `.idea/nihil_templates/<kind>/` (`template.toml` plus a `files/` tree), so they
 * follow the engine's conventions as they change. A kind without one there uses the default bundled with the plugin,
 * which [exportDefaults] copies into the project for editing.
 */
object ScaffoldTemplates {

    const val PROJECT_DIR = ".idea/nihil_templates"
    private const val RESOURCE_ROOT = "nihil_templates"

    class InvalidTemplateException(message: String) : Exception(message)

    fun projectDir(project: Project, kind: ScaffoldKind): File = File(project.basePath.orEmpty(), "$PROJECT_DIR/${kind.id}")

    fun load(project: Project, kind: ScaffoldKind): ScaffoldTemplate {
        val dir = projectDir(project, kind)
        return if (File(dir, "template.toml").isFile) {
            val filesDir = File(dir, "files")
            val files = filesDir.walkTopDown().filter { it.isFile }.associate {
                it.relativeTo(filesDir).invariantSeparatorsPath to it.readText()
            }
            parse(kind, File(dir, "template.toml").readText(), files, "$PROJECT_DIR/${kind.id}")
        } else {
            val entries = bundledEntries(kind)
            val manifest = entries["template.toml"] ?: throw InvalidTemplateException("No bundled template for ${kind.id}")
            val files = entries.filterKeys { it.startsWith("files/") }.mapKeys { it.key.removePrefix("files/") }
            parse(kind, manifest, files, "bundled")
        }
    }

    /** Copies the bundled templates into the project, skipping kinds it already has; returns the kinds written. */
    fun exportDefaults(project: Project): List<ScaffoldKind> = ScaffoldKind.entries.filter { kind ->
        val dir = projectDir(project, kind)
        if (dir.exists()) return@filter false
        for ((path, content) in bundledEntries(kind)) {
            File(dir, path).apply { parentFile.mkdirs() }.writeText(content)
        }
        true
    }

    private fun parse(kind: ScaffoldKind, manifest: String, files: Map<String, String>, origin: String): ScaffoldTemplate {
        val table = SimpleToml.parse(manifest.lines())["template"]
            ?: throw InvalidTemplateException("$origin/template.toml has no [template] table")
        fun string(key: String, required: Boolean = true): String? =
            (table[key] as? String) ?: if (required) throw InvalidTemplateException("$origin/template.toml: missing $key") else null
        return ScaffoldTemplate(
            kind = kind,
            label = string("label", required = false) ?: kind.id,
            description = string("description", required = false).orEmpty(),
            targetDir = string("target_dir")!!,
            registerFile = string("register_file")!!,
            registerSection = string("register_section", required = false)?.takeIf { it.isNotBlank() },
            registerLine = string("register_line")!!,
            open = (table["open"] as? List<*>)?.map { it.toString() }.orEmpty(),
            files = files,
            origin = origin,
        )
    }

    /** Relative path -> text of every bundled file of [kind], read from the plugin's resources (jar or directory). */
    internal fun bundledEntries(kind: ScaffoldKind): Map<String, String> {
        val prefix = "$RESOURCE_ROOT/${kind.id}/"
        val url: URL = ScaffoldTemplates::class.java.classLoader.getResource("${prefix}template.toml") ?: return emptyMap()
        return when (url.protocol) {
            "jar" -> {
                // IntelliJ's class loaders don't hand out a JarURLConnection; open the jar the URL points into.
                val jarFile = File(URI(url.path.substringBefore("!/")))
                JarFile(jarFile).use { jar ->
                    jar.entries().asSequence()
                        .filter { !it.isDirectory && it.name.startsWith(prefix) }
                        .associate { it.name.removePrefix(prefix) to jar.getInputStream(it).readBytes().decodeToString() }
                }
            }
            "file" -> {
                val root = File(url.toURI()).parentFile
                root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }
            }
            else -> throw InvalidTemplateException("Can't read bundled templates from $url")
        }
    }
}
