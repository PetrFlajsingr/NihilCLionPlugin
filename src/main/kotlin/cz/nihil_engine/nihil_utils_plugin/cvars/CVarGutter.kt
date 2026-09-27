package cz.nihil_engine.nihil_utils_plugin.cvars

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.Alarm
import cz.nihil_engine.nihil_utils_plugin.asserts.AssertLocator
import cz.nihil_engine.nihil_utils_plugin.project.NihilFeature
import cz.nihil_engine.nihil_utils_plugin.project.NihilProjectConfigService
import java.awt.Font
import java.awt.Graphics
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseEvent
import javax.swing.Icon

/**
 * While an app is connected: a gutter icon on each cvar/command declaration the app reports, with its current
 * value after the line; clicking the icon edits the value in the running app. `$cvar{"prefix"}` structs get
 * one icon listing their members.
 */
@Service(Service.Level.PROJECT)
class CVarGutter(private val project: Project) : Disposable {

    private class EditorState(val editor: Editor, val alarm: Alarm) {
        var decls: List<CVarDecl> = emptyList()
        var highlighters: List<RangeHighlighter> = emptyList()
        var inlays: List<Inlay<*>> = emptyList()
        var shown: Map<String, String?> = emptyMap()
    }

    @Volatile private var started = false
    private val states = java.util.concurrent.CopyOnWriteArrayList<EditorState>()

    fun start() {
        if (started) return
        started = true
        val live = CVarLiveService.getInstance(project)
        live.addInterest(this) { states.any { it.decls.isNotEmpty() && !it.editor.isDisposed && it.editor.component.isShowing } }
        EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) {
                if (event.editor.project == project) attach(event.editor)
            }
        }, this)
        val refresh = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
        project.messageBus.connect(this).apply {
            subscribe(CVarLiveService.TOPIC, CVarLiveService.Listener {
                refresh.cancelAllRequests()
                refresh.addRequest({ updateAll(rescan = false) }, 100)
            })
            subscribe(NihilProjectConfigService.TOPIC, NihilProjectConfigService.Listener {
                ApplicationManager.getApplication().invokeLater({ updateAll(rescan = true) }, project.disposed)
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

        val disposable = Disposer.newDisposable("Nihil cvar gutter")
        EditorUtil.disposeWithEditor(editor, disposable)
        val state = EditorState(editor, Alarm(Alarm.ThreadToUse.SWING_THREAD, disposable))
        editor.putUserData(STATE_KEY, state)
        states += state
        Disposer.register(disposable) { states -= state }
        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                state.alarm.cancelAllRequests()
                state.alarm.addRequest({ update(state, rescan = true) }, 400)
            }
        }, disposable)
        update(state, rescan = true)
    }

    private fun updateAll(rescan: Boolean) {
        states.filter { !it.editor.isDisposed }.forEach { update(it, rescan) }
    }

    private fun update(state: EditorState, rescan: Boolean) {
        val editor = state.editor
        if (editor.isDisposed) return
        val enabled = NihilProjectConfigService.isEnabled(project, NihilFeature.CVARS)
        if (rescan) state.decls = if (enabled) CVarScanner.scan(editor.document.immutableCharSequence) else emptyList()

        val live = CVarLiveService.getInstance(project).state
        val objects = if (enabled && live.isConnected) live.objects else emptyMap()
        val wanted = state.decls.associate { d -> d.name to (objects[d.name]?.value ?: if (d.kind == CVarKind.PREFIX) prefixMembers(objects, d.name).size.toString() else null) }
            .filterValues { it != null }
        // Values only change on a poll; skip rebuilding markers when nothing shown changed.
        if (!rescan && wanted == state.shown && state.highlighters.all { it.isValid }) return
        state.shown = wanted

        state.highlighters.forEach { it.dispose() }
        state.highlighters = emptyList()
        state.inlays.forEach { Disposer.dispose(it) }
        state.inlays = emptyList()
        if (objects.isEmpty()) return

        val document = editor.document
        val highlighters = mutableListOf<RangeHighlighter>()
        val inlays = mutableListOf<Inlay<*>>()
        for (decl in state.decls) {
            if (decl.start > document.textLength) continue
            val line = document.getLineNumber(decl.start)
            // After the closing quote of the name literal: the end of a long declaration line is often off screen.
            val valueOffset = minOf(decl.nameOffset + decl.name.length + 1, document.textLength)
            val (icon, text) = when (decl.kind) {
                CVarKind.PREFIX -> {
                    val members = prefixMembers(objects, decl.name)
                    if (members.isEmpty()) continue
                    CVarIcon(project, decl, null, members) to "${members.size} live cvars"
                }
                else -> {
                    val obj = objects[decl.name] ?: continue
                    CVarIcon(project, decl, obj, emptyList()) to if (obj.isCommand) null else "= ${obj.value.orEmpty()}"
                }
            }
            highlighters += editor.markupModel.addLineHighlighter(line, HighlighterLayer.ADDITIONAL_SYNTAX, null).apply { gutterIconRenderer = icon }
            if (text != null) {
                editor.inlayModel.addInlineElement(valueOffset, true, LiveValueRenderer(text))?.let { inlays += it }
            }
        }
        state.highlighters = highlighters
        state.inlays = inlays
    }

    override fun dispose() {}

    companion object {
        private val STATE_KEY = Key.create<EditorState>("nihil.cvarGutter")

        fun prefixMembers(objects: Map<String, LiveObject>, prefix: String): List<LiveObject> =
            objects.values.filter { it.name.startsWith("$prefix.") }.sortedBy { it.name }

        fun getInstance(project: Project): CVarGutter = project.getService(CVarGutter::class.java)
    }
}

private class CVarIcon(
    private val project: Project,
    private val decl: CVarDecl,
    private val obj: LiveObject?,
    private val members: List<LiveObject>,
) : GutterIconRenderer() {

    override fun getIcon(): Icon = when {
        obj?.isCommand == true -> AllIcons.Actions.Execute
        obj?.readOnly == true -> AllIcons.Nodes.Variable
        else -> AllIcons.Debugger.Console
    }

    override fun getTooltipText(): String {
        val app = CVarLiveService.getInstance(project).state.appName ?: "the running app"
        fun esc(s: String?) = StringUtil.escapeXmlEntities(s.orEmpty())
        return when {
            obj == null -> "<html><b>${esc(decl.name)}.*</b> in $app:<br>" +
                members.take(20).joinToString("<br>") { "${esc(it.name)} = ${esc(it.value)}" } +
                (if (members.size > 20) "<br>…" else "") + "<br><br>Click to edit one</html>"
            obj.isCommand -> "<html>Console command <b>${esc(obj.name)}</b> in $app<br>${esc(obj.help)}<br><br>Click to run</html>"
            else -> "<html><b>${esc(obj.name)}</b> = ${esc(obj.value)} in $app" +
                (if (obj.readOnly) " (read-only)" else "") + "<br>${esc(obj.help)}<br><br>" +
                "Values are polled: changes made inside the app appear within a poll interval.<br>Click to edit</html>"
        }
    }

    override fun getClickAction(): AnAction = object : DumbAwareAction() {
        override fun actionPerformed(e: AnActionEvent) {
            val where = (e.inputEvent as? MouseEvent)?.let { RelativePoint(it) }
                ?: JBPopupFactory.getInstance().guessBestPopupLocation(e.dataContext)
            if (obj != null) {
                CVarValueEditorPopup.show(project, obj, where)
                return
            }
            JBPopupFactory.getInstance().createListPopup(object : BaseListPopupStep<LiveObject>("${decl.name}.*", members) {
                override fun getTextFor(value: LiveObject) = "${value.name.removePrefix(decl.name + ".")} = ${value.value.orEmpty()}"
                override fun isSpeedSearchEnabled() = true
                override fun onChosen(selectedValue: LiveObject, finalChoice: Boolean): PopupStep<*>? {
                    doFinalStep { CVarValueEditorPopup.show(project, selectedValue, where) }
                    return FINAL_CHOICE
                }
            }).show(where)
        }
    }

    override fun getAlignment(): Alignment = Alignment.LEFT

    override fun isNavigateAction(): Boolean = true

    override fun equals(other: Any?): Boolean =
        other is CVarIcon && other.decl == decl && other.obj == obj && other.members == members

    override fun hashCode(): Int = decl.hashCode()
}

/** `= true` after the cvar's name, in the inlay hint colors. */
private class LiveValueRenderer(private val text: String) : EditorCustomElementRenderer {

    private fun font(editor: Editor): Font = editor.colorsScheme.getFont(EditorFontType.ITALIC)

    override fun calcWidthInPixels(inlay: Inlay<*>): Int =
        inlay.editor.contentComponent.getFontMetrics(font(inlay.editor)).stringWidth(text) + 12

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        val editor = inlay.editor
        val g2 = g.create() as java.awt.Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.font = font(editor)
            g2.color = editor.colorsScheme.getAttributes(DefaultLanguageHighlighterColors.INLAY_DEFAULT)?.foregroundColor ?: JBColor.GRAY
            val metrics = g2.fontMetrics
            g2.drawString(text, targetRegion.x + 6, targetRegion.y + (targetRegion.height + metrics.ascent - metrics.descent) / 2)
        } finally {
            g2.dispose()
        }
    }
}
