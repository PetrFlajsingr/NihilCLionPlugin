package cz.nihil_engine.nihil_utils_plugin.scaffold

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.cidr.cpp.cmake.workspace.CMakeWorkspace

/** One target to create: its rendered files, where it is registered, and what to open. */
data class PlannedTarget(
    val template: ScaffoldTemplate,
    /** Relative to the project root. */
    val dir: String,
    /** Path relative to [dir] -> content. */
    val files: List<Pair<String, String>>,
    val registerLine: String,
    val open: List<String>,
)

object ScaffoldGenerator {

    private val log = Logger.getInstance(ScaffoldGenerator::class.java)

    /** Renders [template]; throws [TemplateRenderer.TemplateException] with the template's origin in the message. */
    fun plan(template: ScaffoldTemplate, vars: Map<String, String>, flags: Set<String>): PlannedTarget {
        fun <T> inTemplate(what: String, block: () -> T): T = try {
            block()
        } catch (e: TemplateRenderer.TemplateException) {
            throw TemplateRenderer.TemplateException("${template.origin}, $what: ${e.message}")
        }

        val files = template.files.entries.sortedBy { it.key }.mapNotNull { (pathTemplate, content) ->
            val path = inTemplate(pathTemplate) { TemplateRenderer.renderPath(pathTemplate, vars) }
            val text = inTemplate(pathTemplate) {
                TemplateRenderer.render(content, vars + ("file_name" to path.substringAfterLast('/')), flags)
            }
            // A file whose whole content is conditional (e.g. prof_categories.ixx) is left out.
            if (text.isBlank()) null else path to text.trimEnd() + "\n"
        }
        return PlannedTarget(
            template = template,
            dir = inTemplate("target_dir") { TemplateRenderer.renderPath(template.targetDir, vars) },
            files = files,
            registerLine = inTemplate("register_line") { TemplateRenderer.renderPath(template.registerLine, vars) },
            open = template.open.map { inTemplate("open") { TemplateRenderer.renderPath(it, vars) } },
        )
    }

    /**
     * Creates the targets' files, registers each in its CMakeLists.txt, opens the files to open and schedules a CMake
     * reload. Runs on the EDT, as one undoable command. Returns warnings for the user.
     */
    fun create(project: Project, targets: List<PlannedTarget>): List<String> {
        val base = LocalFileSystem.getInstance().refreshAndFindFileByPath(project.basePath ?: return listOf("The project has no directory"))
            ?: return listOf("Can't find the project directory")
        val warnings = mutableListOf<String>()
        val toOpen = mutableListOf<VirtualFile>()

        WriteCommandAction.writeCommandAction(project).withName("New Nihil ${targets.first().template.label}").run<Exception> {
            for (target in targets) {
                val dir = VfsUtil.createDirectoryIfMissing(base, target.dir)
                    ?: throw IllegalStateException("Can't create ${target.dir}")
                val created = target.files.associate { (path, content) ->
                    val parent = if ('/' in path) VfsUtil.createDirectoryIfMissing(dir, path.substringBeforeLast('/'))!! else dir
                    val name = path.substringAfterLast('/')
                    val file = parent.findChild(name) ?: parent.createChildData(this, name)
                    VfsUtil.saveText(file, content)
                    path to file
                }
                target.open.forEach { path -> created[path]?.let(toOpen::add) }
                register(base, target)?.let(warnings::add)
            }
        }

        val editors = FileEditorManager.getInstance(project)
        toOpen.asReversed().forEachIndexed { i, file -> editors.openFile(file, i == toOpen.lastIndex) }
        try {
            CMakeWorkspace.getInstance(project).scheduleReload()
        } catch (e: Exception) {
            log.warn("Couldn't schedule a CMake reload", e)
            warnings += "Reload the CMake project to pick up the new target."
        }
        return warnings
    }

    /** Adds the target's add_subdirectory line; returns a warning when that didn't go as planned. */
    private fun register(base: VirtualFile, target: PlannedTarget): String? {
        val template = target.template
        val cmake = base.findFileByRelativePath(template.registerFile)
            ?: return "${template.registerFile} not found: add ${target.registerLine} yourself."
        val document = FileDocumentManager.getInstance().getDocument(cmake)
            ?: return "Can't edit ${template.registerFile}: add ${target.registerLine} yourself."
        val result = CMakeRegistration.insert(document.text, target.registerLine, template.registerSection) ?: return null
        document.setText(result.text)
        FileDocumentManager.getInstance().saveDocument(document)
        return if (result.placed) null
        else "\"${template.registerSection}\" not found in ${template.registerFile}; ${target.registerLine} was added at its end."
    }
}
