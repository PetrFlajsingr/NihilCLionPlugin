package cz.nihil_engine.nihil_utils_plugin.args

import com.intellij.icons.AllIcons
import com.intellij.ide.setToolTipText
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.fields.ExtendableTextComponent
import com.intellij.ui.components.fields.ExtendableTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.nio.file.Path
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.event.DocumentEvent


internal class ArgsPanelContext(
    val project: Project,
    val service: NihilArgsConfigService,
    val profile: TargetProfile,
    val targetName: String,
    val layoutChanged: () -> Unit,
) {
    private val refreshers = mutableListOf<(Map<String, ArgTemplates.Resolved>) -> Unit>()

    fun onChange(refresher: (Map<String, ArgTemplates.Resolved>) -> Unit) {
        refreshers += refresher
    }

    fun changed() {
        val resolved = resolved()
        refreshers.forEach { it(resolved) }
    }

    fun resolved(): Map<String, ArgTemplates.Resolved> = service.resolvedValues(profile, targetName)
}

internal object NihilArgRowFactory {

    fun createArgRow(ctx: ArgsPanelContext, arg: ArgDefinition): JComponent = when (arg.type) {
        ArgType.BOOL -> createBoolRow(ctx, arg)
        ArgType.SELECT -> createSelectRow(ctx, arg)
        ArgType.TEXT -> createLabeledRow(arg.label, tooltip(arg), templateField(ctx, arg, browse = false))
        ArgType.PATH -> createLabeledRow(arg.label, tooltip(arg), templateField(ctx, arg, browse = true))
        ArgType.INT -> createIntRow(ctx, arg)
        ArgType.MULTI -> createMultiRow(ctx, arg)
        ArgType.DERIVED -> throw IllegalStateException("DERIVED args should not reach createArgRow")
    }

    fun createLabeledRow(label: String, tooltip: String, control: JComponent): JComponent {
        val labelCell = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JBLabel("$label:").apply {
                toolTipText = tooltip
                preferredSize = Dimension(140, preferredSize.height)
            }, BorderLayout.NORTH)
        }
        return object : JPanel(BorderLayout(8, 0)) {
            override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)
        }.apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(labelCell, BorderLayout.WEST)
            add(control, BorderLayout.CENTER)
        }
    }

    private fun tooltip(arg: ArgDefinition): String =
        if (arg.default.isEmpty()) arg.flag
        else "<html>${StringUtil.escapeXmlEntities(arg.flag)}<br>Default: ${StringUtil.escapeXmlEntities(arg.default)}</html>"

    private fun createBoolRow(ctx: ArgsPanelContext, arg: ArgDefinition): JComponent =
        JBCheckBox(arg.label, ctx.service.getBoolValue(ctx.profile, arg)).apply {
            toolTipText = arg.flag
            alignmentX = Component.LEFT_ALIGNMENT
            addActionListener {
                ctx.service.setBoolValue(ctx.profile, arg, isSelected)
                ctx.changed()
            }
        }

    private fun createSelectRow(ctx: ArgsPanelContext, arg: ArgDefinition): JComponent {
        val combo = ComboBox(arg.options.toTypedArray()).apply {
            selectedItem = ctx.service.getValue(ctx.profile, arg)
            addActionListener {
                val selected = selectedItem as? String ?: return@addActionListener
                ctx.service.setValue(ctx.profile, arg, selected)
                ctx.changed()
            }
        }
        return createLabeledRow(arg.label, arg.flag, combo)
    }

    private fun templateField(ctx: ArgsPanelContext, arg: ArgDefinition, browse: Boolean): JComponent {
        val field = ExtendableTextField(ctx.service.getValue(ctx.profile, arg), 20)
        val reset = ExtendableTextComponent.Extension.create(
            AllIcons.General.Reset, AllIcons.General.Reset,
            "Reset to default: ${arg.default.ifEmpty { "(empty)" }}",
        ) { field.text = arg.default }
        fun updateReset() = field.setExtensions(if (field.text != arg.default) listOf(reset) else emptyList())
        updateReset()

        val preview = JBLabel().apply {
            font = JBUI.Fonts.smallFont()
            border = JBUI.Borders.emptyLeft(4)
        }
        updatePreview(preview, field.text, ctx.resolved()[arg.key])
        ctx.onChange { resolved ->
            val wasVisible = preview.isVisible
            updatePreview(preview, field.text, resolved[arg.key])
            if (preview.isVisible != wasVisible) ctx.layoutChanged()
        }

        field.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                ctx.service.setValue(ctx.profile, arg, field.text)
                updateReset()
                ctx.changed()
            }
        })

        val input = if (!browse) field else JPanel(BorderLayout(4, 0)).apply {
            isOpaque = false
            add(field, BorderLayout.CENTER)
            add(JButton("...").apply {
                isFocusable = false
                addActionListener { choosePath(arg, ctx.project)?.let { field.text = it } }
            }, BorderLayout.EAST)
        }
        return JPanel(BorderLayout(0, 2)).apply {
            isOpaque = false
            add(input, BorderLayout.NORTH)
            add(preview, BorderLayout.CENTER)
        }
    }

    private fun updatePreview(label: JBLabel, raw: String, resolved: ArgTemplates.Resolved?) {
        label.isVisible = resolved != null && ArgTemplates.hasReferences(raw)
        if (!label.isVisible || resolved == null) return
        val shown = resolved.value.ifEmpty { "(empty)" }
        if (resolved.problems.isEmpty()) {
            label.text = "= $shown"
            label.foreground = UIUtil.getContextHelpForeground()
            label.setToolTipText(HtmlChunk.text(resolved.value))
        } else {
            label.text = "= $shown  (${resolved.problems.joinToString("; ")})"
            label.foreground = JBColor.RED
            label.setToolTipText(HtmlChunk.text(resolved.problems.joinToString("\n")))
        }
    }

    private fun choosePath(arg: ArgDefinition, project: Project): String? =
        if (arg.pathDirection == PathDirection.OUTPUT && arg.pathKind == PathKind.FILE) {
            val nullPath: Path? = null
            FileChooserFactory.getInstance()
                .createSaveFileDialog(FileSaverDescriptor(arg.label, ""), project)
                .save(nullPath, null)
                ?.file?.absolutePath
        } else {
            val descriptor = if (arg.pathKind == PathKind.DIRECTORY)
                FileChooserDescriptorFactory.singleDir()
            else
                FileChooserDescriptorFactory.singleFile()
            FileChooser.chooseFile(descriptor, project, null)?.path
        }

    private fun createIntRow(ctx: ArgsPanelContext, arg: ArgDefinition): JComponent {
        val current = ctx.service.getValue(ctx.profile, arg).toIntOrNull() ?: arg.default.toIntOrNull() ?: 0
        val spinner = JSpinner(SpinnerNumberModel(current, arg.min ?: Int.MIN_VALUE, arg.max ?: Int.MAX_VALUE, 1)).apply {
            addChangeListener {
                ctx.service.setValue(ctx.profile, arg, value.toString())
                ctx.changed()
            }
        }
        return createLabeledRow(arg.label, arg.flag, spinner)
    }

    private fun createMultiRow(ctx: ArgsPanelContext, arg: ArgDefinition): JComponent {
        val selected = ctx.service.getMultiValue(ctx.profile, arg).toMutableSet()
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(JBLabel("${arg.label}:").apply {
                toolTipText = arg.flag
                alignmentX = Component.LEFT_ALIGNMENT
            })
            for (option in arg.options) {
                add(JBCheckBox(option, option in selected).apply {
                    alignmentX = Component.LEFT_ALIGNMENT
                    border = JBUI.Borders.emptyLeft(16)
                    addActionListener {
                        if (isSelected) selected.add(option) else selected.remove(option)
                        ctx.service.setMultiValue(ctx.profile, arg, arg.options.filter { it in selected })
                        ctx.changed()
                    }
                })
            }
        }
    }
}
