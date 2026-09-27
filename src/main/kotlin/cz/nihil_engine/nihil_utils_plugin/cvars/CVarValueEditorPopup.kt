package cz.nihil_engine.nihil_utils_plugin.cvars

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * A small editor for one live console object: a checkbox for bools, a dropdown for enums and set limiters, a
 * checked number field for ranges and quantities, a text field otherwise, and an argument field for commands.
 * Changes go to the running app through [CVarLiveService]; its answer (or silence) is shown in place.
 */
object CVarValueEditorPopup {

    fun show(project: Project, obj: LiveObject, where: RelativePoint) {
        val live = CVarLiveService.getInstance(project)
        val editor = ValueEditor.forObject(obj)
        val status = JBLabel(" ").apply { componentStyle = UIUtil.ComponentStyle.SMALL }
        val header = JBLabel(headerText(obj.name, obj.value))
        var popup: JBPopup? = null
        var focus: JComponent? = null

        fun showError(text: String) {
            status.foreground = JBColor.RED
            status.text = "<html><div style=\"width:${JBUI.scale(380)}px\">${StringUtil.escapeXmlEntities(text)}</div></html>"
            popup?.pack(true, true)
        }

        fun send(value: String, closeOnSuccess: Boolean) {
            status.foreground = UIUtil.getContextHelpForeground()
            status.text = "Setting…"
            live.set(obj.name, value).thenAccept { result ->
                ApplicationManager.getApplication().invokeLater({
                    when {
                        result.error != null -> showError(result.error)
                        closeOnSuccess -> popup?.cancel()
                        else -> {
                            status.foreground = UIUtil.getContextHelpForeground()
                            status.text = "Set"
                            header.text = headerText(obj.name, result.obj?.value ?: value)
                        }
                    }
                }, ModalityState.any())
            }
        }

        val control: JComponent = when (editor) {
            ValueEditor.ReadOnly -> JBLabel(obj.value ?: "").also { status.text = if (obj.readOnly) "Read-only" else "Not a variable" }
            ValueEditor.Bool -> JBCheckBox("Enabled", obj.value == "true" || obj.value == "1").apply {
                focus = this
                addActionListener { send(if (isSelected) "true" else "false", closeOnSuccess = false) }
            }
            is ValueEditor.Choice -> ComboBox(editor.options.toTypedArray()).apply {
                focus = this
                selectedItem = obj.value
                addActionListener { (selectedItem as? String)?.let { if (it != obj.value) send(it, closeOnSuccess = false) } }
            }
            ValueEditor.Command -> {
                val args = JBTextField(24).apply { emptyText.text = "arguments (optional)" }
                focus = args
                row(args, JButton("Run").apply {
                    addActionListener {
                        live.exec((obj.name + " " + args.text.trim()).trim())
                        status.text = "Sent; output is in the Nihil Console Variables tool window"
                    }
                })
            }
            else -> {
                val field = JBTextField(ValueEditing.initialText(obj, editor), 16)
                focus = field
                val apply = {
                    val error = ValueEditing.validate(field.text, editor)
                    if (error != null) showError(error) else send(ValueEditing.normalize(field.text, editor), closeOnSuccess = true)
                }
                field.addActionListener { apply() }
                val hint = when (editor) {
                    is ValueEditor.Number -> listOfNotNull(
                        editor.unit,
                        if (editor.min != null || editor.max != null)
                            "${editor.min?.let { ValueEditing.formatNumber(it, editor.integral) } ?: "…"} to ${editor.max?.let { ValueEditing.formatNumber(it, editor.integral) } ?: "…"}"
                        else null,
                    ).joinToString(", ")
                    is ValueEditor.Vector -> "${editor.count} values, comma separated"
                    else -> ""
                }
                row(field, JBLabel(hint).apply { componentStyle = UIUtil.ComponentStyle.SMALL }, JButton("Set").apply { addActionListener { apply() } })
            }
        }

        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8)
            add(left(header))
            if (obj.help.isNotBlank()) add(left(JBLabel(obj.help).apply { componentStyle = UIUtil.ComponentStyle.SMALL; foreground = UIUtil.getContextHelpForeground() }))
            add(left(control))
            add(left(status))
            add(left(JBLabel("Values are re-read periodically; changes made inside the app show up on the next poll.").apply {
                componentStyle = UIUtil.ComponentStyle.MINI
                foreground = UIUtil.getContextHelpForeground()
            }))
        }
        popup = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, focus ?: control)
            .setRequestFocus(true)
            .setFocusable(true)
            .setMovable(true)
            .setCancelOnClickOutside(true)
            .setCancelKeyEnabled(true)
            .createPopup()
        popup.show(where)
    }

    private fun headerText(name: String, value: String?) =
        "<html><b>${StringUtil.escapeXmlEntities(name)}</b> = ${StringUtil.escapeXmlEntities(value.orEmpty())}</html>"

    private fun row(vararg components: JComponent) = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
        components.forEach(::add)
    }

    private fun left(component: JComponent) = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.emptyBottom(4)
        add(component, BorderLayout.WEST)
    }
}
