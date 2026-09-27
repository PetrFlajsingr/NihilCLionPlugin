package cz.nihil_engine.nihil_utils_plugin.feature_flags

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.execution.ExecutionTargetListener
import com.intellij.execution.ExecutionTargetManager
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import cz.nihil_engine.nihil_utils_plugin.project.NihilFeature
import cz.nihil_engine.nihil_utils_plugin.project.NihilProjectConfigService
import cz.nihil_engine.nihil_utils_plugin.util.TableFit
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Font
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.RowFilter
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableRowSorter

class FeatureFlagsToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun shouldBeAvailable(project: Project): Boolean =
        NihilProjectConfigService.isEnabled(project, NihilFeature.FEATURE_FLAGS)

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        project.messageBus.connect(toolWindow.disposable).subscribe(NihilProjectConfigService.TOPIC, NihilProjectConfigService.Listener {
            ApplicationManager.getApplication().invokeLater({
                toolWindow.isAvailable = NihilProjectConfigService.isEnabled(project, NihilFeature.FEATURE_FLAGS)
            }, project.disposed)
        })
        val panel = FeatureFlagsPanel(project, toolWindow.disposable)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, "", false).also {
            it.putUserData(PANEL_KEY, panel)
        })
    }

    companion object {
        const val ID = "Nihil Feature Flags"
        private val PANEL_KEY = com.intellij.openapi.util.Key.create<FeatureFlagsPanel>("nihil.featureFlagsPanel")

        /** Opens the tool window, selecting [flag] when given. */
        fun show(project: Project, flag: String? = null) {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ID) ?: return
            toolWindow.activate {
                val panel = toolWindow.contentManager.contents.firstNotNullOfOrNull { it.getUserData(PANEL_KEY) }
                if (flag != null) panel?.select(flag)
            }
        }
    }
}

/** NihilEngine menu: "Feature Flag Matrix". */
class ShowFeatureFlagsAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = NihilProjectConfigService.isEnabled(e.project, NihilFeature.FEATURE_FLAGS)
    }

    override fun actionPerformed(e: AnActionEvent) {
        FeatureFlagsToolWindowFactory.show(e.project ?: return)
    }
}

/** The Feature table has three text columns before the build types. */
private const val FIRST_BT_COLUMN = 3

/** The flag table shows the build types right after the name, so the matrix is visible without scrolling. */
private const val FLAG_FIRST_BT_COLUMN = 1

private class FeatureFlagsPanel(private val project: Project, disposable: Disposable) : SimpleToolWindowPanel(true, true) {

    private val service = FeatureFlagModelService.getInstance(project)
    private var model: FeatureFlagModel = FeatureFlagModel.EMPTY
    private var active: BuildType? = null

    private val status = JBLabel().apply { border = JBUI.Borders.empty(2, 6) }
    private val search = SearchTextField(false)

    private val flagModel = FlagTableModel()
    private val flagTable = JBTable(flagModel)
    private val flagSorter = TableRowSorter(flagModel)

    private val featureModel = FeatureTableModel()
    private val featureTable = JBTable(featureModel)
    private val featureSorter = TableRowSorter(featureModel)

    private val profileModel = ProfileTableModel()
    private val profileTable = JBTable(profileModel)

    init {
        flagTable.rowSorter = flagSorter
        featureTable.rowSorter = featureSorter
        for (table in listOf(flagTable, featureTable, profileTable)) {
            table.setDefaultRenderer(Any::class.java, CellRenderer())
            table.setShowGrid(false)
        }
        flagTable.emptyText.text = "Building the flag model…"

        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean = navigateFlag(event)
        }.installOn(flagTable)
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val row = featureTable.rowAtPoint(event.point).takeIf { it >= 0 }?.let(featureTable::convertRowIndexToModel) ?: return false
                val constant = featureModel.rows[row]
                val bt = BuildType.entries.getOrNull(featureTable.convertColumnIndexToModel(featureTable.columnAtPoint(event.point)) - FIRST_BT_COLUMN)
                (bt?.let { constant.links[it] } ?: constant.links.values.firstOrNull())?.let(service::navigate)
                return true
            }
        }.installOn(featureTable)
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val row = profileTable.rowAtPoint(event.point).takeIf { it >= 0 } ?: return false
                val column = profileTable.columnAtPoint(event.point)
                profileModel.linkAt(row, column)?.let(service::navigate) ?: return false
                return true
            }
        }.installOn(profileTable)

        search.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = applyFilter()
        })

        val tabs = JBTabbedPane().apply {
            addTab("Flags", ScrollPaneFactory.createScrollPane(flagTable))
            addTab("Feature Constants", ScrollPaneFactory.createScrollPane(featureTable))
            addTab("CMake Profiles", ScrollPaneFactory.createScrollPane(profileTable))
        }
        val top = JPanel(BorderLayout()).apply {
            add(search, BorderLayout.CENTER)
            add(status, BorderLayout.SOUTH)
        }
        val content = JPanel(BorderLayout()).apply {
            add(top, BorderLayout.NORTH)
            add(tabs, BorderLayout.CENTER)
        }
        setContent(content)
        toolbar = ActionManager.getInstance().createActionToolbar("NihilFeatureFlags", DefaultActionGroup().apply {
            add(object : DumbAwareAction("Rebuild", "Re-read config headers, NihilFlags.cmake and the CMake profiles", AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) = service.invalidate()
            })
        }, true).apply { targetComponent = content }.component

        project.messageBus.connect(disposable).apply {
            subscribe(FeatureFlagModelService.TOPIC, FeatureFlagModelService.Listener { ApplicationManager.getApplication().invokeLater({ refresh() }, project.disposed) })
            subscribe(ExecutionTargetManager.TOPIC, ExecutionTargetListener { ApplicationManager.getApplication().invokeLater({ refresh() }, project.disposed) })
        }
        service.request()
        refresh()
    }

    fun select(flag: String) {
        search.text = ""
        val row = flagModel.rows.indexOfFirst { it.name == flag }.takeIf { it >= 0 } ?: return
        val view = flagTable.convertRowIndexToView(row)
        flagTable.selectionModel.setSelectionInterval(view, view)
        flagTable.scrollRectToVisible(flagTable.getCellRect(view, 0, true))
    }

    private fun refresh() {
        model = service.model ?: FeatureFlagModel.EMPTY
        val profile = model.profile(service.activeProfileName())
        active = profile?.buildType
        status.text = buildString {
            append(if (profile != null) "Active profile: ${profile.name} → ${profile.buildType?.displayName ?: "unknown build type"}" else "No active CMake profile")
            append(".  ✓ enabled  ✗ disabled  ? can't tell  ~ differs between profiles/files  – not defined  * #ifndef default  c compiler-dependent")
            if (model.problems.isNotEmpty()) append("  Problems: ${model.problems.joinToString("; ")}")
        }
        flagModel.rows = model.flags.values.sortedWith(compareBy({ it.kind.ordinal }, { it.owner }, { it.name }))
        featureModel.rows = model.features
        profileModel.update(model)
        flagModel.fireTableStructureChanged()
        featureModel.fireTableStructureChanged()
        profileModel.fireTableStructureChanged()
        sizeColumns()
        applyFilter()
    }

    private fun sizeColumns() {
        TableFit.fit(flagTable)
        TableFit.fit(featureTable, maxWidth = 420)
        TableFit.fit(profileTable)
    }

    private fun applyFilter() {
        val needle = search.text.trim()
        val pattern = "(?i)" + Regex.escape(needle)
        val flagColumns = intArrayOf(0, FLAG_FIRST_BT_COLUMN + BuildType.entries.size, FLAG_FIRST_BT_COLUMN + BuildType.entries.size + 1)
        flagSorter.rowFilter = if (needle.isEmpty()) null else RowFilter.regexFilter(pattern, *flagColumns)
        featureSorter.rowFilter = if (needle.isEmpty()) null else RowFilter.regexFilter(pattern, 0, 1, 2)
    }

    private fun navigateFlag(event: MouseEvent): Boolean {
        val viewRow = flagTable.rowAtPoint(event.point).takeIf { it >= 0 } ?: return false
        val info = flagModel.rows[flagTable.convertRowIndexToModel(viewRow)]
        val column = flagTable.convertColumnIndexToModel(flagTable.columnAtPoint(event.point))
        val bt = flagModel.buildTypeAt(column)
        val link = bt?.let { cellsOf(info)[it]?.reasons?.firstNotNullOfOrNull { r -> r.link } } ?: info.definitions.firstOrNull()
        link?.let(service::navigate)
        return link != null
    }

    private fun cellsOf(info: FlagInfo) = FlagPresentation.effectiveCells(info, null, active, model.profile(service.activeProfileName()))

    private inner class FlagTableModel : AbstractTableModel() {
        var rows: List<FlagInfo> = emptyList()

        private val kindColumn = FLAG_FIRST_BT_COLUMN + BuildType.entries.size

        fun buildTypeAt(column: Int): BuildType? = if (column < kindColumn) BuildType.entries.getOrNull(column - FLAG_FIRST_BT_COLUMN) else null

        override fun getRowCount() = rows.size
        override fun getColumnCount() = kindColumn + 2
        override fun getColumnName(column: Int) = when (column) {
            0 -> "Flag"
            kindColumn -> "Kind"
            kindColumn + 1 -> "Source"
            else -> buildTypeAt(column)!!.let { if (it == active) "${it.short} ●" else it.short }
        }

        override fun getValueAt(row: Int, column: Int): Any {
            val info = rows[row]
            return when (column) {
                0 -> info.name
                kindColumn -> info.kind.label
                kindColumn + 1 -> info.owner
                else -> cellsOf(info).getValue(buildTypeAt(column)!!)
            }
        }

        fun tooltip(row: Int, column: Int): String? {
            val info = rows[row]
            val bt = buildTypeAt(column)
                ?: return (listOfNotNull(info.note) + info.definitions.map { "${it.fileName}:${it.line + 1}" }).joinToString("<br>").ifEmpty { null }
            val cell = cellsOf(info).getValue(bt)
            return "<b>${bt.displayName}</b>: ${cell.state.description}<br>" + cell.reasons.joinToString("<br>") { StringUtil.escapeXmlEntities(it.text) }
        }
    }

    private inner class FeatureTableModel : AbstractTableModel() {
        var rows: List<FeatureConstant> = emptyList()

        override fun getRowCount() = rows.size
        override fun getColumnCount() = FIRST_BT_COLUMN + BuildType.entries.size
        override fun getColumnName(column: Int) = when (column) {
            0 -> "Feature"
            1 -> "Library"
            2 -> "Description"
            else -> BuildType.entries[column - FIRST_BT_COLUMN].let { if (it == active) "${it.displayName} ●" else it.displayName }
        }

        override fun getValueAt(row: Int, column: Int): Any {
            val constant = rows[row]
            return when (column) {
                0 -> "config::${constant.name}"
                1 -> constant.owner
                2 -> constant.description
                else -> constant.values[BuildType.entries[column - FIRST_BT_COLUMN]] ?: "–"
            }
        }
    }

    /** One row per CMake profile: its build type, the CMake-driven flags and the options they come from. */
    private inner class ProfileTableModel : AbstractTableModel() {
        private var profiles: List<ResolvedProfile> = emptyList()
        private var flags: List<String> = emptyList()
        private var variables: List<String> = emptyList()

        fun update(model: FeatureFlagModel) {
            profiles = model.profiles
            flags = model.flags.values.filter { it.kind == FlagKind.CMAKE }.map { it.name }
            variables = model.profiles.flatMap { p -> p.variables.map { it.name } }.distinct()
        }

        override fun getRowCount() = profiles.size
        override fun getColumnCount() = 2 + flags.size + variables.size
        override fun getColumnName(column: Int) = when {
            column == 0 -> "Profile"
            column == 1 -> "Build type"
            column < 2 + flags.size -> flags[column - 2]
            else -> variables[column - 2 - flags.size]
        }

        override fun getValueAt(row: Int, column: Int): Any {
            val p = profiles[row]
            return when {
                column == 0 -> p.name + if (p.enabled) "" else " (disabled)"
                column == 1 -> p.buildType?.displayName ?: "?"
                column < 2 + flags.size -> p.flags[flags[column - 2]]?.state?.symbol ?: "?"
                else -> p.variables.firstOrNull { it.name == variables[column - 2 - flags.size] }?.value ?: "?"
            }
        }

        fun tooltip(row: Int, column: Int): String? {
            val p = profiles[row]
            return when {
                column == 1 -> p.buildTypeReason
                column in 2 until 2 + flags.size -> p.flags[flags[column - 2]]?.reason
                column >= 2 + flags.size -> p.variables.firstOrNull { it.name == variables[column - 2 - flags.size] }?.origin?.label
                else -> null
            }
        }

        fun linkAt(row: Int, column: Int): SourceLink? =
            if (column in 2 until 2 + flags.size) profiles[row].flags[flags[column - 2]]?.link else null

        fun isActive(row: Int) = profiles[row].name == service.activeProfileName()
    }

    private inner class CellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
        ): Component {
            val modelRow = table.convertRowIndexToModel(row)
            val modelColumn = table.convertColumnIndexToModel(column)
            val cell = value as? FlagCell
            super.getTableCellRendererComponent(table, cell?.symbol ?: value, isSelected, hasFocus, row, column)
            horizontalAlignment = if (cell != null || (table === profileTable && modelColumn >= 2)) SwingConstants.CENTER else SwingConstants.LEADING
            val bt = when (table) {
                flagTable -> flagModel.buildTypeAt(modelColumn)
                featureTable -> BuildType.entries.getOrNull(modelColumn - FIRST_BT_COLUMN)
                else -> null
            }
            val activeColumn = bt != null && bt == active && table !== profileTable
            val activeRow = table === profileTable && profileModel.isActive(modelRow)
            font = if (activeColumn || activeRow) font.deriveFont(Font.BOLD) else font.deriveFont(Font.PLAIN)
            if (!isSelected) foreground = when (cell?.state) {
                CellState.DISABLED -> JBColor.GRAY
                CellState.UNKNOWN, CellState.MIXED, CellState.UNDEFINED -> JBColor(0xB26B00, 0xD9A343)
                else -> table.foreground
            }
            toolTipText = when (table) {
                flagTable -> flagModel.tooltip(modelRow, modelColumn)?.let { "<html>$it</html>" }
                profileTable -> profileModel.tooltip(modelRow, modelColumn)
                else -> null
            }
            return this
        }
    }
}
