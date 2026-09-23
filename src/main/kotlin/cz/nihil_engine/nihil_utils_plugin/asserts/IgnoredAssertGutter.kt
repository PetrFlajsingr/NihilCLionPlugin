package cz.nihil_engine.nihil_utils_plugin.asserts

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.Alarm
import cz.nihil_engine.nihil_utils_plugin.project.NihilFeature
import cz.nihil_engine.nihil_utils_plugin.project.NihilProjectConfigService
import java.awt.event.MouseEvent
import javax.swing.Icon

@Service(Service.Level.PROJECT)
class IgnoredAssertGutter(private val project: Project) : Disposable {

    private class EditorState(val alarm: Alarm) {
        var highlighters: List<RangeHighlighter> = emptyList()
    }

    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) {
                if (event.editor.project == project) attach(event.editor)
            }
        }, this)
        project.messageBus.connect(this).apply {
            subscribe(IgnoreListService.TOPIC, IgnoreListService.Listener { updateAll() })
            subscribe(NihilProjectConfigService.TOPIC, NihilProjectConfigService.Listener {
                ApplicationManager.getApplication().invokeLater({ updateAll() }, project.disposed)
            })
        }
        ApplicationManager.getApplication().invokeLater({
            EditorFactory.getInstance().allEditors.filter { it.project == project }.forEach(::attach)
        }, project.disposed)
    }

    private fun attach(editor: Editor) {
        if (editor.getUserData(STATE_KEY) != null) return
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        if (file.extension?.lowercase() !in AssertLocator.SOURCE_EXTENSIONS) return

        val disposable = Disposer.newDisposable("Nihil ignored assert markers")
        EditorUtil.disposeWithEditor(editor, disposable)
        val state = EditorState(Alarm(Alarm.ThreadToUse.SWING_THREAD, disposable))
        editor.putUserData(STATE_KEY, state)
        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                state.alarm.cancelAllRequests()
                state.alarm.addRequest({ update(editor) }, 300)
            }
        }, disposable)
        update(editor)
    }

    private fun updateAll() {
        EditorFactory.getInstance().allEditors.filter { it.project == project && !it.isDisposed }.forEach(::update)
    }

    private fun update(editor: Editor) {
        val state = editor.getUserData(STATE_KEY) ?: return
        if (editor.isDisposed) return
        state.highlighters.forEach { it.dispose() }
        state.highlighters = emptyList()

        if (!NihilProjectConfigService.isEnabled(project, NihilFeature.IGNORED_ASSERTS)) return
        val service = IgnoreListService.getInstance(project)
        if (service.lists.all { it.ids.isEmpty() }) return

        state.highlighters = AssertSite.findAll(editor.document.immutableCharSequence, AssertMacroService.getInstance(project).macros).mapNotNull { site ->
            val lists = service.listsIgnoring(site.id)
            if (lists.isEmpty()) return@mapNotNull null
            editor.markupModel.addLineHighlighter(site.line, HighlighterLayer.ADDITIONAL_SYNTAX, null).apply {
                gutterIconRenderer = IgnoredAssertIcon(project, site, lists)
            }
        }
    }

    override fun dispose() {}

    companion object {
        private val STATE_KEY = Key.create<EditorState>("nihil.ignoredAssertGutter")

        fun getInstance(project: Project): IgnoredAssertGutter = project.getService(IgnoredAssertGutter::class.java)
    }
}

private class IgnoredAssertIcon(
    private val project: Project,
    private val site: AssertSite,
    private val lists: List<IgnoreList>,
) : GutterIconRenderer() {

    override fun getIcon(): Icon = AllIcons.Debugger.Db_muted_breakpoint

    override fun getTooltipText(): String {
        val where = lists.joinToString("<br>") {
            "&nbsp;&nbsp;${StringUtil.escapeXmlEntities(it.name)} <small>(${StringUtil.escapeXmlEntities(it.file.path)})</small>"
        }
        return "<html>${site.idText} is ignored: the program doesn't break on it. Listed in<br>$where<br><br>Click for options</html>"
    }

    override fun getClickAction(): AnAction = object : DumbAwareAction() {
        override fun actionPerformed(e: AnActionEvent) {
            val group = DefaultActionGroup().apply {
                add(DumbAwareAction.create(if (lists.size == 1) "Stop Ignoring ${site.idText}" else "Stop Ignoring ${site.idText} Everywhere") {
                    IgnoreListService.getInstance(project).unignore(site.id)
                })
                if (lists.size > 1) {
                    for (list in lists) {
                        add(DumbAwareAction.create("Stop Ignoring in ${list.name}") {
                            IgnoreListService.getInstance(project).unignore(site.id, list)
                        })
                    }
                }
                addSeparator()
                add(DumbAwareAction.create("Show Ignored Asserts") {
                    ToolWindowManager.getInstance(project).getToolWindow(IgnoredAssertsToolWindowFactory.ID)?.activate(null)
                })
            }
            val popup = JBPopupFactory.getInstance().createActionGroupPopup(
                "${site.kind.typeName} ${site.idText}", group, e.dataContext,
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false,
            )
            val mouse = e.inputEvent as? MouseEvent
            if (mouse != null) popup.show(RelativePoint(mouse)) else popup.showInBestPositionFor(e.dataContext)
        }
    }

    override fun getAlignment(): Alignment = Alignment.RIGHT

    override fun isNavigateAction(): Boolean = true

    override fun equals(other: Any?): Boolean =
        other is IgnoredAssertIcon && other.site == site && other.lists == lists

    override fun hashCode(): Int = site.hashCode() * 31 + lists.hashCode()
}
