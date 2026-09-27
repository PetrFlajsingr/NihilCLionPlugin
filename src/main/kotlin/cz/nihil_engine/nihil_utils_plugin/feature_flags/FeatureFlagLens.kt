package cz.nihil_engine.nihil_utils_plugin.feature_flags

import com.intellij.codeInsight.hint.HintUtil
import com.intellij.execution.ExecutionTargetListener
import com.intellij.execution.ExecutionTargetManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.Alarm
import cz.nihil_engine.nihil_utils_plugin.asserts.AssertLocator
import cz.nihil_engine.nihil_utils_plugin.project.NihilFeature
import cz.nihil_engine.nihil_utils_plugin.project.NihilProjectConfigService
import java.awt.Cursor
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import javax.swing.event.HyperlinkEvent

/**
 * Inlays after every `NIHIL_IS_ENABLED(X)` with X in all five build types, and warnings where a file uses a
 * library's config symbol without including that library's config.hpp.
 *
 * Inlays are added through the editor's InlayModel directly, managed per editor like the ignored-assert gutter
 * markers: on Nova, C++ files have no PSI for InlayHintsProvider to walk, and the lens needs nothing but text.
 */
@Service(Service.Level.PROJECT)
class FeatureFlagLens(private val project: Project) : Disposable {

    private class Warning(val highlighter: RangeHighlighter, val symbol: String, val include: String)

    private class EditorState(val alarm: Alarm, val hoverAlarm: Alarm) {
        var inlays: List<Inlay<FlagInlayRenderer>> = emptyList()
        var warnings: List<Warning> = emptyList()
        var hovered: Any? = null
        var popup: JBPopup? = null
        var logged: Pair<Int, Int>? = null
    }

    private val log = Logger.getInstance(FeatureFlagLens::class.java)

    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) {
                if (event.editor.project == project) attach(event.editor)
            }
        }, this)
        val later = { ApplicationManager.getApplication().invokeLater({ updateAll() }, project.disposed) }
        project.messageBus.connect(this).apply {
            subscribe(FeatureFlagModelService.TOPIC, FeatureFlagModelService.Listener { later() })
            // The active profile decides which build type is emphasised.
            subscribe(ExecutionTargetManager.TOPIC, ExecutionTargetListener { later() })
            subscribe(NihilProjectConfigService.TOPIC, NihilProjectConfigService.Listener { later() })
        }
        ApplicationManager.getApplication().invokeLater({
            EditorFactory.getInstance().allEditors.filter { it.project == project }.forEach(::attach)
        }, project.disposed)
    }

    private fun attach(editor: Editor) {
        if (editor.getUserData(STATE_KEY) != null) return
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        if (file.extension?.lowercase() !in AssertLocator.SOURCE_EXTENSIONS) return

        val disposable = Disposer.newDisposable("Nihil feature flag lens")
        EditorUtil.disposeWithEditor(editor, disposable)
        val state = EditorState(Alarm(Alarm.ThreadToUse.SWING_THREAD, disposable), Alarm(Alarm.ThreadToUse.SWING_THREAD, disposable))
        editor.putUserData(STATE_KEY, state)
        Disposer.register(disposable) { state.popup?.cancel() }
        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                state.alarm.cancelAllRequests()
                state.alarm.addRequest({ update(editor) }, 300)
            }
        }, disposable)
        editor.addEditorMouseListener(object : EditorMouseListener {
            override fun mouseClicked(e: EditorMouseEvent) {
                val inlay = flagInlayAt(e) ?: return
                showFlagPopup(editor, inlay, hover = false)
                e.consume()
            }
        }, disposable)
        editor.addEditorMouseMotionListener(object : EditorMouseMotionListener {
            override fun mouseMoved(e: EditorMouseEvent) = hover(editor, state, e)
        }, disposable)
        update(editor)
    }

    private fun flagInlayAt(e: EditorMouseEvent): Inlay<FlagInlayRenderer>? {
        val inlay = e.inlay ?: return null
        @Suppress("UNCHECKED_CAST")
        return if (inlay.renderer is FlagInlayRenderer) inlay as Inlay<FlagInlayRenderer> else null
    }

    private fun hover(editor: Editor, state: EditorState, e: EditorMouseEvent) {
        val inlay = flagInlayAt(e)
        val warning = if (inlay == null && e.isOverText) state.warnings.firstOrNull { w ->
            w.highlighter.isValid && e.offset in w.highlighter.startOffset until w.highlighter.endOffset
        } else null
        val target: Any? = inlay ?: warning
        if (target === state.hovered) return
        state.hovered = target
        state.hoverAlarm.cancelAllRequests()
        (editor as? EditorEx)?.setCustomCursor(this, if (inlay != null) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else null)
        when {
            inlay != null -> state.hoverAlarm.addRequest({ if (state.hovered === inlay && inlay.isValid) showFlagPopup(editor, inlay, hover = true) }, HOVER_DELAY_MS)
            warning != null -> state.hoverAlarm.addRequest({ if (state.hovered === warning) showWarningPopup(editor, warning, e.mouseEvent.point) }, HOVER_DELAY_MS)
        }
    }

    private fun updateAll() {
        EditorFactory.getInstance().allEditors.filter { it.project == project && !it.isDisposed }.forEach(::update)
    }

    private fun update(editor: Editor) {
        val state = editor.getUserData(STATE_KEY) ?: return
        if (editor.isDisposed) return
        state.inlays.forEach { Disposer.dispose(it) }
        state.inlays = emptyList()
        state.warnings.forEach { it.highlighter.dispose() }
        state.warnings = emptyList()

        if (!NihilProjectConfigService.isEnabled(project, NihilFeature.FEATURE_FLAGS)) return
        val service = FeatureFlagModelService.getInstance(project)
        val model = service.request() ?: return
        val path = FileDocumentManager.getInstance().getFile(editor.document)?.path
        val text = editor.document.immutableCharSequence
        val activeProfile = model.profile(service.activeProfileName())
        val active = activeProfile?.buildType

        state.inlays = CppFlagScanner.uses(text).mapNotNull { use ->
            val info = model.flags[use.name]
            val cells = info?.let { FlagPresentation.effectiveCells(it, path, active, activeProfile) }
            val renderer = FlagInlayRenderer(use.name, FlagPresentation.segments(info, cells, active))
            editor.inlayModel.addInlineElement(use.end, true, renderer)
        }

        val check = service.includeCheck
        if (path != null && check != null && !check.isEmpty) {
            state.warnings = check.check(path, text).flatMap { missing ->
                missing.symbols.map { (symbol, offset) ->
                    val end = offset + symbol.length
                    val highlighter = editor.markupModel.addRangeHighlighter(
                        CodeInsightColors.WARNINGS_ATTRIBUTES, offset, end, HighlighterLayer.WARNING, HighlighterTargetArea.EXACT_RANGE,
                    )
                    highlighter.errorStripeTooltip = warningText(symbol, missing.include)
                    Warning(highlighter, symbol, missing.include)
                }
            }
        }
        val counts = state.inlays.size to state.warnings.size
        if (counts != state.logged && (counts.first > 0 || counts.second > 0)) {
            log.debug("Feature flag lens: ${path?.substringAfterLast('/')}: ${counts.first} inlays, ${counts.second} include warnings")
        }
        state.logged = counts
    }

    private fun warningText(symbol: String, include: String) =
        "$symbol comes from <$include>, which this file doesn't include directly"

    private fun showFlagPopup(editor: Editor, inlay: Inlay<FlagInlayRenderer>, hover: Boolean) {
        val service = FeatureFlagModelService.getInstance(project)
        val model = service.model ?: return
        val name = inlay.renderer.flag
        val info = model.flags[name]
        val activeProfile = model.profile(service.activeProfileName())
        val path = FileDocumentManager.getInstance().getFile(editor.document)?.path
        val cells = info?.let { FlagPresentation.effectiveCells(it, path, activeProfile?.buildType, activeProfile) }
        val links = mutableListOf<SourceLink>()
        val html = FlagPresentation.html(name, info, cells, activeProfile?.buildType, activeProfile, links)
        val bounds = inlay.bounds ?: return
        show(editor, html, Point(bounds.x, bounds.y + bounds.height), hover, bounds) { href ->
            when (href) {
                "matrix" -> FeatureFlagsToolWindowFactory.show(project, name)
                else -> href.toIntOrNull()?.let(links::getOrNull)?.let(service::navigate)
            }
        }
    }

    private fun showWarningPopup(editor: Editor, warning: Warning, at: Point) {
        val html = "<html><body>" + StringUtil.escapeXmlEntities(warningText(warning.symbol, warning.include)) +
            ".<br>It builds only while another header pulls it in (the engine's check-config-includes.py rule)." +
            "<br><br><a href=\"add\">Add #include &lt;${StringUtil.escapeXmlEntities(warning.include)}&gt;</a></body></html>"
        val lineHeight = editor.lineHeight
        show(editor, html, Point(at.x, at.y + lineHeight), hover = true, anchor = Rectangle(at.x - 4, at.y - lineHeight, 8, lineHeight * 2)) { href ->
            if (href == "add") addInclude(editor, warning.include)
        }
    }

    private fun show(editor: Editor, html: String, at: Point, hover: Boolean, anchor: Rectangle, onLink: (String) -> Unit) {
        val state = editor.getUserData(STATE_KEY) ?: return
        state.popup?.cancel()
        var popup: JBPopup? = null
        val component = HintUtil.createInformationLabel(html, { e ->
            if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                popup?.cancel()
                onLink(e.description)
            }
        }, null, null)
        popup = JBPopupFactory.getInstance().createComponentPopupBuilder(component, null)
            .setRequestFocus(!hover)
            .setFocusable(!hover)
            .setResizable(false)
            .setMovable(false)
            .setCancelOnClickOutside(true)
            .setCancelKeyEnabled(true)
            .apply {
                if (hover) setCancelOnMouseOutCallback { e ->
                    val onScreen = Point(anchor.location).also { javax.swing.SwingUtilities.convertPointToScreen(it, editor.contentComponent) }
                    !Rectangle(onScreen, anchor.size).apply { grow(2, 2) }.contains(e.locationOnScreen)
                }
            }
            .createPopup()
        state.popup = popup
        popup.show(RelativePoint(editor.contentComponent, at))
    }

    private fun addInclude(editor: Editor, include: String) {
        val document = editor.document
        val lines = document.charsSequence.lines()
        val lastInclude = lines.indexOfLast { INCLUDE_LINE.containsMatchIn(it) }
        val anchor = if (lastInclude >= 0) lastInclude
        else lines.indexOfFirst { it.trim() == "module;" || it.trim() == "#pragma once" }
        val offset = if (anchor < 0) 0 else document.getLineEndOffset(anchor)
        val text = if (anchor < 0) "#include <$include>\n" else "\n#include <$include>"
        WriteCommandAction.runWriteCommandAction(project, "Add #include <$include>", null, {
            document.insertString(offset, text)
        })
    }

    override fun dispose() {}

    companion object {
        private val STATE_KEY = Key.create<EditorState>("nihil.featureFlagLens")
        private val INCLUDE_LINE = Regex("""^\s*#\s*include\b""")
        private const val HOVER_DELAY_MS = 500

        fun getInstance(project: Project): FeatureFlagLens = project.getService(FeatureFlagLens::class.java)
    }
}

/** `D✓ Dv✓ P✓ R✗ T✓` drawn like an inlay hint, with the active build type in bold. */
class FlagInlayRenderer(val flag: String, private val segments: List<FlagPresentation.Segment>) : EditorCustomElementRenderer {

    private fun fonts(editor: Editor): Pair<Font, Font> {
        val base = editor.colorsScheme.getFont(EditorFontType.PLAIN)
        val plain = base.deriveFont(maxOf(base.size2D - 1f, 8f))
        return plain to plain.deriveFont(Font.BOLD)
    }

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val (plain, bold) = fonts(inlay.editor)
        val component = inlay.editor.contentComponent
        val space = component.getFontMetrics(plain).stringWidth(" ")
        return PADDING * 2 + segments.withIndex().sumOf { (i, s) ->
            component.getFontMetrics(if (s.active) bold else plain).stringWidth(s.text) + if (i > 0) space else 0
        }
    }

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        val editor = inlay.editor
        val attributes = editor.colorsScheme.getAttributes(DefaultLanguageHighlighterColors.INLAY_DEFAULT)
        val foreground = attributes?.foregroundColor ?: JBColor.GRAY
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            attributes?.backgroundColor?.let {
                g2.color = it
                g2.fillRoundRect(targetRegion.x, targetRegion.y + 1, targetRegion.width, targetRegion.height - 2, 8, 8)
            }
            val (plain, bold) = fonts(editor)
            val metrics = editor.contentComponent.getFontMetrics(plain)
            val baseline = targetRegion.y + (targetRegion.height + metrics.ascent - metrics.descent) / 2
            var x = targetRegion.x + PADDING
            val space = metrics.stringWidth(" ")
            for ((i, segment) in segments.withIndex()) {
                if (i > 0) x += space
                val font = if (segment.active) bold else plain
                g2.font = font
                g2.color = when (segment.state) {
                    CellState.ENABLED, CellState.COMPILER, null -> foreground
                    CellState.DISABLED -> JBColor(0x9E9E9E, 0x6E6E6E)
                    CellState.UNKNOWN, CellState.MIXED, CellState.UNDEFINED -> JBColor(0xB26B00, 0xD9A343)
                }
                g2.drawString(segment.text, x, baseline)
                if (segment.active) {
                    val width = g2.getFontMetrics(font).stringWidth(segment.text)
                    g2.drawLine(x, baseline + 2, x + width, baseline + 2)
                }
                x += g2.getFontMetrics(font).stringWidth(segment.text)
            }
        } finally {
            g2.dispose()
        }
    }

    companion object {
        private const val PADDING = 5
    }
}
