package cz.nihil_engine.nihil_utils_plugin.scaffold

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.TableSpeedSearch
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.io.File
import java.time.LocalDate
import javax.swing.JComponent
import javax.swing.event.DocumentEvent
import javax.swing.table.AbstractTableModel

/**
 * "New Nihil Library, App or Tool": picks a kind, fills in the template's variables, previews what will be created and
 * creates it (see [ScaffoldGenerator]).
 */
class NewNihilModuleDialog(private val project: Project) : DialogWrapper(project, true) {

    private val root = File(project.basePath.orEmpty())
    private val libraryNames = EngineTree.libraryNames(root)
    private val templates = HashMap<ScaffoldKind, Result<ScaffoldTemplate>>()

    private val kindButtons = linkedMapOf(
        ScaffoldKind.LIBRARY to JBRadioButton("Library", true),
        ScaffoldKind.INTERFACE_LIBRARY to JBRadioButton("Interface library"),
        ScaffoldKind.APP to JBRadioButton("Application"),
        ScaffoldKind.TOOL to JBRadioButton("Tool"),
    )
    private val nameField = JBTextField(24)
    private val groupCombo = ComboBox(EngineTree.groups(root).toTypedArray()).apply { isEditable = true }
    private val moduleField = JBTextField(24)
    private val namespaceField = JBTextField(24)
    private val descriptionField = JBTextField(40)
    private val authorField = JBTextField(gitUserName() ?: System.getProperty("user.name").orEmpty(), 24)
    private val profilingBox = JBCheckBox("Profiling categories (prof_categories.ixx, depends on Profiling)")
    private val testsBox = JBCheckBox("Test target (src/tests/Nihil<Name>, NihilTest<Name>)")
    private val dependencies = DependencyModel(libraryNames)
    private val dependencyTable = JBTable(dependencies).apply {
        setShowGrid(false)
        columnModel.getColumn(0).apply { maxWidth = JBUI.scale(30) }
        columnModel.getColumn(2).apply { maxWidth = JBUI.scale(60) }
        TableSpeedSearch.installOn(this)
    }
    private val preview = JBTextArea(12, 60).apply {
        isEditable = false
        font = JBUI.Fonts.create(java.awt.Font.MONOSPACED, font.size)
    }
    private val templateLink = ActionLink("") { exportTemplates() }
    private val dependenciesLabel = JBLabel("Dependencies:")

    private lateinit var groupRow: Row
    private lateinit var moduleRow: Row
    private lateinit var namespaceRow: Row
    private lateinit var profilingRow: Row
    private lateinit var testsRow: Row

    /** Module and namespace follow the name until edited by hand. */
    private var moduleEdited = false
    private var namespaceEdited = false
    private var settingDerived = false

    private val kind: ScaffoldKind get() = kindButtons.entries.first { it.value.isSelected }.key

    init {
        title = "New Nihil Library, App or Tool"
        kindButtons.values.forEach { it.addActionListener { kindChanged() } }

        nameField.document.addDocumentListener(onChange {
            settingDerived = true
            val lower = nameField.text.trim().lowercase()
            if (!moduleEdited) moduleField.text = lower
            if (!namespaceEdited) namespaceField.text = if (lower.isEmpty()) "" else "nihil::$lower"
            settingDerived = false
        })
        moduleField.document.addDocumentListener(onChange { if (!settingDerived) moduleEdited = moduleField.text.isNotEmpty() })
        namespaceField.document.addDocumentListener(onChange { if (!settingDerived) namespaceEdited = namespaceField.text.isNotEmpty() })
        listOf(nameField, moduleField, namespaceField, descriptionField, authorField).forEach {
            it.document.addDocumentListener(onChange { updatePreview() })
        }
        (groupCombo.editor.editorComponent as? JBTextField)?.document?.addDocumentListener(onChange { updatePreview() })
        groupCombo.addActionListener { updatePreview() }
        profilingBox.addActionListener {
            if (profilingBox.isSelected) dependencies.select("Profiling", public = true)
            updatePreview()
        }
        testsBox.addActionListener { updatePreview() }
        dependencies.addTableModelListener { updatePreview() }

        init()
        kindChanged()
    }

    override fun createCenterPanel(): JComponent = panel {
        // The UI DSL makes the buttons exclusive; it requires radio buttons inside a buttonsGroup.
        buttonsGroup {
            row("Kind:") { kindButtons.values.forEach { cell(it) } }
        }
        row("Name:") {
            cell(nameField).focused().comment("PascalCase, without the Nihil prefix, e.g. SceneGraph")
        }
        groupRow = row("Group:") { cell(groupCombo).comment("Domain directory under src/") }
        moduleRow = row("Module:") { cell(moduleField).comment("Imported as nihil.&lt;module&gt;") }
        namespaceRow = row("Namespace:") { cell(namespaceField) }
        row("Description:") { cell(descriptionField).align(Align.FILL) }
        row("Author:") { cell(authorField) }
        profilingRow = row { cell(profilingBox) }
        testsRow = row { cell(testsBox) }
        row { cell(dependenciesLabel) }
        row {
            cell(ScrollPaneFactory.createScrollPane(dependencyTable)).align(Align.FILL)
                .applyToComponent { preferredSize = JBUI.size(460, 180) }
        }.resizableRow()
        row { label("Will create:") }
        row {
            cell(ScrollPaneFactory.createScrollPane(preview)).align(Align.FILL).align(AlignY.FILL)
        }.resizableRow()
        row { cell(templateLink) }
    }

    override fun getPreferredFocusedComponent(): JComponent = nameField

    private fun kindChanged() {
        val k = kind
        val library = k == ScaffoldKind.LIBRARY
        groupRow.visible(k == ScaffoldKind.LIBRARY || k == ScaffoldKind.INTERFACE_LIBRARY)
        moduleRow.visible(library)
        namespaceRow.visible(library)
        profilingRow.visible(library)
        testsRow.visible(library)
        dependencies.showPublic = library
        dependencies.reset(defaultDependencies(k))
        dependencyTable.columnModel.getColumn(2).apply {
            maxWidth = if (library) JBUI.scale(60) else 0
            minWidth = 0
            preferredWidth = if (library) JBUI.scale(60) else 0
        }
        dependenciesLabel.text = when (k) {
            ScaffoldKind.LIBRARY -> "Dependencies (Public: PUBLIC_DEPENDENCIES, otherwise PRIVATE_DEPENDENCIES):"
            ScaffoldKind.APP -> "Extra dependencies (nihil_application always links the standard app set):"
            else -> "Dependencies:"
        }
        updatePreview()
        pack()
    }

    /** The defaults the existing targets of each kind use (DebugDraw, LuaDefs); only ones that exist. */
    private fun defaultDependencies(k: ScaffoldKind): Map<String, Boolean> = when (k) {
        ScaffoldKind.LIBRARY -> mapOf("Core" to true, "Common" to true, "AllocStatics" to false) +
            (if (profilingBox.isSelected) mapOf("Profiling" to true) else emptyMap())
        ScaffoldKind.TOOL -> listOf("Core", "Macros", "Cli", "Config", "Common", "Log", "ToolsBase", "AllocStatics").associateWith { false }
        else -> emptyMap()
    }.filterKeys { it in libraryNames }

    // ---------- variables and plan

    private fun template(k: ScaffoldKind): ScaffoldTemplate =
        templates.getOrPut(k) { runCatching { ScaffoldTemplates.load(project, k) } }.getOrThrow()

    private fun vars(): Map<String, String> {
        val name = nameField.text.trim()
        val sanitized = name.replace('.', '_')
        val module = moduleField.text.trim().ifEmpty { sanitized.lowercase() }
        fun list(names: List<String>) = names.joinToString("\n") { "NihilEngine::$it" }
        return mapOf(
            "Name" to name,
            "name_lower" to sanitized.lowercase(),
            "NAME_UPPER" to sanitized.uppercase(),
            "module" to module,
            "module_file" to module.replace('.', '_'),
            "namespace" to namespaceField.text.trim().ifEmpty { "nihil::${sanitized.lowercase()}" },
            "group" to groupText(),
            "description" to descriptionField.text.trim(),
            "author" to authorField.text.trim(),
            "year" to LocalDate.now().year.toString(),
            "public_dependencies" to list(dependencies.selected(public = true)),
            "private_dependencies" to list(dependencies.selected(public = false)),
            "dependencies" to list(dependencies.selected(public = null)),
        )
    }

    private fun flags(vars: Map<String, String>): Set<String> = buildSet {
        if (kind == ScaffoldKind.LIBRARY && profilingBox.isSelected) add("profiling")
        if (vars.getValue("public_dependencies").isNotEmpty()) add("has_public_dependencies")
        if (vars.getValue("private_dependencies").isNotEmpty()) add("has_private_dependencies")
        if (vars.getValue("dependencies").isNotEmpty()) add("has_dependencies")
    }

    private fun plan(): List<PlannedTarget> {
        val vars = vars()
        val flags = flags(vars)
        val targets = mutableListOf(ScaffoldGenerator.plan(template(kind), vars, flags))
        if (kind == ScaffoldKind.LIBRARY && testsBox.isSelected) {
            val name = vars.getValue("Name")
            val testDeps = listOf("Common", name, "Log", "AllocStatics").filter { it == name || it in libraryNames }
            val testVars = vars + ("dependencies" to testDeps.joinToString("\n") { "NihilEngine::$it" })
            targets += ScaffoldGenerator.plan(template(ScaffoldKind.LIBRARY_TEST), testVars, flags(testVars))
        }
        return targets
    }

    private fun updatePreview() {
        val template = runCatching { template(kind) }
        templateLink.text = template.fold(
            onSuccess = {
                if (it.origin == "bundled") "Templates: bundled with the plugin. Export to ${ScaffoldTemplates.PROJECT_DIR} to customize…"
                else "Templates: ${ScaffoldTemplates.PROJECT_DIR} (edit the files there to change what is created)"
            },
            onFailure = { "Template error: ${it.message}" },
        )
        templateLink.isEnabled = template.getOrNull()?.origin == "bundled"
        if (nameField.text.isBlank()) {
            preview.text = ""
            return
        }
        preview.text = try {
            plan().joinToString("\n\n") { target ->
                buildString {
                    append("${target.template.label}  (${target.template.origin} template)\n")
                    append("  ${target.dir}/\n")
                    target.files.forEach { (path, _) -> append("    $path\n") }
                    val where = target.template.registerSection?.let { " after \"$it\"" }.orEmpty()
                    append("  + ${target.registerLine}  in ${target.template.registerFile}$where")
                }
            } + "\n\nThen: open the new files and reload the CMake project."
        } catch (e: Exception) {
            "Can't render: ${e.message}"
        }
        preview.caretPosition = 0
    }

    // ---------- validation and creation

    override fun doValidate(): ValidationInfo? {
        val name = nameField.text.trim()
        if (!NAME.matches(name)) return ValidationInfo("PascalCase letters and digits, e.g. SceneGraph", nameField)
        if (kind == ScaffoldKind.TOOL && name.startsWith("Nihil")) {
            return ValidationInfo("Leave out the Nihil prefix: the target becomes Nihil$name", nameField)
        }
        if ((kind == ScaffoldKind.LIBRARY || kind == ScaffoldKind.INTERFACE_LIBRARY) && name in libraryNames) {
            return ValidationInfo("NihilEngine::$name already exists", nameField)
        }
        if ((kind == ScaffoldKind.LIBRARY || kind == ScaffoldKind.INTERFACE_LIBRARY) && !GROUP.matches(groupText())) return ValidationInfo("Lowercase directory name, e.g. data", groupCombo)
        if (kind == ScaffoldKind.LIBRARY) {
            if (!MODULE.matches(moduleField.text.trim())) return ValidationInfo("Lowercase module name, e.g. scenegraph", moduleField)
            if (!NAMESPACE.matches(namespaceField.text.trim())) return ValidationInfo("A C++ namespace, e.g. nihil::scene", namespaceField)
        }
        val targets = try {
            plan()
        } catch (e: Exception) {
            return ValidationInfo("Template error: ${e.message}")
        }
        targets.firstOrNull { File(root, it.dir).exists() }?.let {
            return ValidationInfo("${it.dir} already exists", nameField)
        }
        return null
    }

    // For tests: drive the dialog without showing it.
    internal fun selectForTest(kind: ScaffoldKind, name: String) {
        kindButtons.getValue(kind).doClick()
        nameField.text = name
    }

    internal fun validationForTest(): String? = doValidate()?.message

    internal fun previewForTest(): String = preview.text

    override fun doOKAction() {
        val targets = try {
            plan()
        } catch (e: Exception) {
            Messages.showErrorDialog(project, e.message, title)
            return
        }
        super.doOKAction()
        val warnings = ScaffoldGenerator.create(project, targets)
        if (warnings.isNotEmpty()) Messages.showWarningDialog(project, warnings.joinToString("\n"), title)
    }

    private fun exportTemplates() {
        val written = ScaffoldTemplates.exportDefaults(project)
        LocalFileSystem.getInstance().refreshAndFindFileByPath("${project.basePath}/${ScaffoldTemplates.PROJECT_DIR}")
            ?.refresh(false, true)
        templates.clear()
        updatePreview()
        Messages.showInfoMessage(
            project,
            if (written.isEmpty()) "${ScaffoldTemplates.PROJECT_DIR} already has every template."
            else "Wrote ${written.joinToString { it.id }} to ${ScaffoldTemplates.PROJECT_DIR}. Edit them there; commit them to share.",
            "Export Templates",
        )
    }

    private fun groupText(): String =
        ((groupCombo.editor.editorComponent as? JBTextField)?.text ?: groupCombo.selectedItem?.toString()).orEmpty().trim()

    private fun gitUserName(): String? = try {
        val process = ProcessBuilder("git", "config", "user.name").directory(root).redirectErrorStream(true).start()
        if (process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() == 0) {
            process.inputStream.bufferedReader().readText().trim().ifEmpty { null }
        } else null
    } catch (_: Exception) {
        null
    }

    private fun onChange(block: () -> Unit) = object : DocumentAdapter() {
        override fun textChanged(e: DocumentEvent) = block()
    }

    companion object {
        private val NAME = Regex("""[A-Z][A-Za-z0-9]*""")
        private val GROUP = Regex("""[a-z][a-z0-9_]*""")
        private val MODULE = Regex("""[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)*""")
        private val NAMESPACE = Regex("""[A-Za-z_]\w*(::[A-Za-z_]\w*)*""")
    }
}

/** Existing library targets with "use" and "public" checkboxes. */
private class DependencyModel(private val names: List<String>) : AbstractTableModel() {
    private val use = BooleanArray(names.size)
    private val public = BooleanArray(names.size)
    var showPublic = true

    fun reset(defaults: Map<String, Boolean>) {
        names.forEachIndexed { i, name ->
            use[i] = name in defaults
            public[i] = defaults[name] == true
        }
        fireTableDataChanged()
    }

    fun select(name: String, public: Boolean) {
        val i = names.indexOf(name).takeIf { it >= 0 } ?: return
        use[i] = true
        this.public[i] = public
        fireTableRowsUpdated(i, i)
    }

    /** Selected names: public or private ones for a library, all of them when [public] is null. */
    fun selected(public: Boolean?): List<String> = names.filterIndexed { i, _ ->
        use[i] && (public == null || this.public[i] == public)
    }

    override fun getRowCount() = names.size
    override fun getColumnCount() = 3
    override fun getColumnName(column: Int) = when (column) {
        0 -> ""
        1 -> "NihilEngine::"
        else -> if (showPublic) "Public" else ""
    }
    override fun getColumnClass(column: Int): Class<*> = if (column == 1) String::class.java else java.lang.Boolean::class.java
    override fun isCellEditable(row: Int, column: Int) = column == 0 || (column == 2 && showPublic && use[row])
    override fun getValueAt(row: Int, column: Int): Any = when (column) {
        0 -> use[row]
        1 -> names[row]
        else -> public[row]
    }
    override fun setValueAt(value: Any?, row: Int, column: Int) {
        when (column) {
            0 -> use[row] = value == true
            2 -> public[row] = value == true
        }
        fireTableRowsUpdated(row, row)
    }
}
