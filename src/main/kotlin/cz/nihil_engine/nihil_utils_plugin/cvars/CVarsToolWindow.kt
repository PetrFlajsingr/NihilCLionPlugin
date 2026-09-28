package cz.nihil_engine.nihil_utils_plugin.cvars

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.JBSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import cz.nihil_engine.nihil_utils_plugin.project.NihilFeature
import cz.nihil_engine.nihil_utils_plugin.project.NihilProjectConfigService
import cz.nihil_engine.nihil_utils_plugin.util.TableFit
import java.awt.BorderLayout
import java.awt.Point
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.RowFilter
import javax.swing.event.DocumentEvent
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableRowSorter

class CVarsToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun shouldBeAvailable(project: Project): Boolean =
        NihilProjectConfigService.isEnabled(project, NihilFeature.CVARS)

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = CVarsPanel(project, toolWindow, toolWindow.disposable)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, "", false).also { it.putUserData(PANEL_KEY, panel) })
    }

    companion object {
        const val ID = "Nihil Console Variables"
        private val PANEL_KEY = Key.create<CVarsPanel>("nihil.cvarsPanel")

        fun show(project: Project, name: String? = null) {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ID) ?: return
            toolWindow.activate {
                if (name != null) toolWindow.contentManager.contents.firstNotNullOfOrNull { it.getUserData(PANEL_KEY) }?.select(name)
            }
        }
    }
}

private class CVarsPanel(private val project: Project, private val toolWindow: ToolWindow, disposable: Disposable) :
    SimpleToolWindowPanel(true, true) {

    private val live = CVarLiveService.getInstance(project)
    private val status = JBLabel().apply { border = JBUI.Borders.empty(2, 6) }
    private val search = SearchTextField(false)
    private val model = Model()
    private val table = JBTable(model)
    private val sorter = TableRowSorter(model)
    private val output = JBTextArea().apply { isEditable = false; lineWrap = false }
    private val execField = JBTextField().apply { emptyText.text = "Console line to run in the app, e.g. r.dump_rdg or r.exposure 2.0 (Enter)" }
    private val refreshAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, disposable)
    private var shownOutput = 0

    init {
        table.rowSorter = sorter
        table.setShowGrid(false)
        table.emptyText.text = "No app connected"
        table.emptyText.appendSecondaryText(
            "Run or debug a Nihil app: the plugin connects to its console control port.", com.intellij.ui.SimpleTextAttributes.GRAYED_ATTRIBUTES, null,
        )
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val viewRow = table.rowAtPoint(event.point).takeIf { it >= 0 } ?: return false
                val obj = model.rows[table.convertRowIndexToModel(viewRow)]
                if (table.convertColumnIndexToModel(table.columnAtPoint(event.point)) == 1) {
                    CVarValueEditorPopup.show(project, obj, RelativePoint(table, Point(event.x, event.y)))
                } else {
                    GoToCVarAction.navigate(project, obj.name)
                }
                return true
            }
        }.installOn(table)
        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = applyFilter()
        })
        execField.addActionListener {
            val line = execField.text.trim()
            if (line.isNotEmpty() && live.state.isConnected) {
                live.exec(line)
                execField.text = ""
            }
        }

        val top = JPanel(BorderLayout()).apply {
            add(search, BorderLayout.CENTER)
            add(status, BorderLayout.SOUTH)
        }
        val bottom = JPanel(BorderLayout()).apply {
            add(ScrollPaneFactory.createScrollPane(output), BorderLayout.CENTER)
            add(execField, BorderLayout.SOUTH)
        }
        val splitter = JBSplitter(true, "Nihil.CVars.Splitter", 0.75f).apply {
            firstComponent = ScrollPaneFactory.createScrollPane(table)
            secondComponent = bottom
        }
        setContent(JPanel(BorderLayout()).apply {
            add(top, BorderLayout.NORTH)
            add(splitter, BorderLayout.CENTER)
        })
        toolbar = ActionManager.getInstance().createActionToolbar("NihilCVars", actions(), true).apply { targetComponent = table }.component

        live.addInterest(disposable) { toolWindow.isVisible }
        project.messageBus.connect(disposable).subscribe(CVarLiveService.TOPIC, CVarLiveService.Listener {
            refreshAlarm.cancelAllRequests()
            refreshAlarm.addRequest({ refresh() }, 100)
        })
        refresh()
    }

    fun select(name: String) {
        search.text = ""
        val row = model.rows.indexOfFirst { it.name == name }.takeIf { it >= 0 } ?: return
        val view = table.convertRowIndexToView(row)
        table.selectionModel.setSelectionInterval(view, view)
        table.scrollRectToVisible(table.getCellRect(view, 0, true))
    }

    private fun refresh() {
        val state = live.state
        val pollMs = NihilProjectConfigService.getInstance(project).config.cvars.pollIntervalMs
        status.text = when {
            state.isConnected -> "${state.status.label}: ${state.appName ?: "app"} on port ${state.port}, ${state.objects.size} objects. " +
                "The app doesn't report changes, so values are re-read every ${pollMs / 1000.0} s while shown." +
                (state.message?.let { "  $it" } ?: "")
            state.status == CVarLiveService.Status.CONNECTING -> "Connecting to ${state.appName ?: "the app"} on port ${state.port}…"
            else -> "Not connected." + (state.message?.let { " $it." } ?: "") + " Run or debug a Nihil app, or use Connect."
        }
        val selected = table.selectedRow.takeIf { it >= 0 }?.let { model.rows[table.convertRowIndexToModel(it)].name }
        val names = CVarNameCache.getInstance(project).snapshot()
        val newRows = state.objects.values.sortedBy { it.name }
        val structureChanged = newRows.map { it.name } != model.rows.map { it.name }
        model.rows = newRows
        model.names = names
        if (structureChanged) {
            model.fireTableDataChanged()
            TableFit.fit(table, maxWidth = 600)
            selected?.let(::select)
        } else if (model.rowCount > 0) {
            // Never with an empty model: rows 0..0 would name a row the sorter doesn't have.
            model.fireTableRowsUpdated(0, model.rowCount - 1)
        }
        val lines = live.output
        if (lines.size != shownOutput) {
            shownOutput = lines.size
            output.text = lines.joinToString("\n")
            output.caretPosition = output.document.length
        }
    }

    private fun applyFilter() {
        val needle = search.text.trim()
        sorter.rowFilter = if (needle.isEmpty()) null else RowFilter.regexFilter("(?i)" + Regex.escape(needle), 0, 3)
    }

    private fun actions() = DefaultActionGroup().apply {
        add(object : DumbAwareAction("Connect…", "Connect to an app's console control port", AllIcons.Actions.Execute) {
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = !live.state.isConnected
            }
            override fun actionPerformed(e: AnActionEvent) {
                val default = NihilProjectConfigService.getInstance(project).config.cvars.port
                val port = Messages.showInputDialog(project, "Port of the running app's console control server:", "Connect", null, default.toString(), null)
                    ?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return
                live.connect(port, owner = null, appName = "port $port")
            }
        })
        add(object : DumbAwareAction("Disconnect", "Close the connection; the app keeps running", AllIcons.Actions.Suspend) {
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = live.state.status != CVarLiveService.Status.DISCONNECTED
            }
            override fun actionPerformed(e: AnActionEvent) = live.disconnect("Disconnected by request")
        })
        add(object : DumbAwareAction("Refresh Now", "Re-read every value from the app", AllIcons.Actions.Refresh) {
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = live.state.isConnected
            }
            override fun actionPerformed(e: AnActionEvent) = live.refresh()
        })
        addSeparator()
        add(object : DumbAwareAction("Clear Output", null, AllIcons.Actions.GC) {
            override fun actionPerformed(e: AnActionEvent) {
                output.text = ""
            }
        })
    }

    private class Model : AbstractTableModel() {
        var rows: List<LiveObject> = emptyList()
        var names: CVarNameCache.Names = CVarNameCache.Names(emptySet(), emptySet())

        override fun getRowCount() = rows.size
        override fun getColumnCount() = 5
        override fun getColumnName(column: Int) = listOf("Name", "Value", "Kind", "Help", "Declared")[column]

        override fun getValueAt(row: Int, column: Int): Any {
            val o = rows[row]
            return when (column) {
                0 -> o.name
                1 -> if (o.isCommand) "" else o.value.orEmpty()
                2 -> when {
                    o.isCommand -> "command"
                    o.readOnly -> "read-only"
                    o.kind == "var" -> "cvar"
                    else -> o.kind
                }
                3 -> o.help
                else -> when {
                    o.name in names.names -> "in source"
                    names.prefixes.any { o.name.startsWith("$it.") } -> "via ${names.prefixes.first { o.name.startsWith("$it.") }}.*"
                    else -> "runtime name"
                }
            }
        }
    }
}
