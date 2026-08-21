package cz.nihil_engine.nihil_utils_plugin.args

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
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

internal object NihilArgRowFactory {

    fun createArgRow(
        service: NihilArgsConfigService,
        profile: TargetProfile,
        arg: ArgDefinition,
        project: Project,
        onChanged: () -> Unit = {},
    ): JComponent = when (arg.type) {
        ArgType.BOOL -> createBoolRow(service, profile, arg, onChanged)
        ArgType.SELECT -> createSelectRow(service, profile, arg, onChanged)
        ArgType.TEXT -> createTextRow(service, profile, arg, onChanged)
        ArgType.PATH -> createPathRow(service, profile, arg, project, onChanged)
        ArgType.INT -> createIntRow(service, profile, arg, onChanged)
        ArgType.MULTI -> createMultiRow(service, profile, arg, onChanged)
        ArgType.DERIVED -> throw IllegalStateException("DERIVED args should not reach createArgRow")
    }

    fun createLabeledRow(label: String, tooltip: String, control: JComponent): JComponent {
        return JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(JBLabel("$label:").apply {
                toolTipText = tooltip
                preferredSize = Dimension(140, preferredSize.height)
            }, BorderLayout.WEST)
            add(control, BorderLayout.CENTER)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height + 8)
        }
    }

    private fun createBoolRow(
        service: NihilArgsConfigService,
        profile: TargetProfile,
        arg: ArgDefinition,
        onChanged: () -> Unit,
    ): JComponent = JBCheckBox(arg.label, service.getBoolValue(profile, arg)).apply {
        toolTipText = arg.flag
        alignmentX = Component.LEFT_ALIGNMENT
        addActionListener {
            service.setBoolValue(profile, arg, isSelected)
            onChanged()
        }
    }

    private fun createSelectRow(
        service: NihilArgsConfigService,
        profile: TargetProfile,
        arg: ArgDefinition,
        onChanged: () -> Unit,
    ): JComponent {
        val combo = ComboBox(arg.options.toTypedArray()).apply {
            selectedItem = service.getValue(profile, arg)
            addActionListener {
                val selected = selectedItem as? String ?: return@addActionListener
                service.setValue(profile, arg, selected)
                onChanged()
            }
        }
        return createLabeledRow(arg.label, arg.flag, combo)
    }

    private fun createTextRow(
        service: NihilArgsConfigService,
        profile: TargetProfile,
        arg: ArgDefinition,
        onChanged: () -> Unit,
    ): JComponent {
        val field = JBTextField(service.getValue(profile, arg)).apply {
            columns = 20
            addActionListener {
                service.setValue(profile, arg, text)
                onChanged()
            }
            addFocusListener(object : java.awt.event.FocusAdapter() {
                override fun focusLost(e: java.awt.event.FocusEvent?) {
                    service.setValue(profile, arg, text)
                    onChanged()
                }
            })
        }
        return createLabeledRow(arg.label, arg.flag, field)
    }

    private fun createPathRow(
        service: NihilArgsConfigService,
        profile: TargetProfile,
        arg: ArgDefinition,
        project: Project,
        onChanged: () -> Unit,
    ): JComponent {
        val field = JBTextField(service.getValue(profile, arg)).apply {
            columns = 20
            addActionListener { service.setValue(profile, arg, text); onChanged() }
            addFocusListener(object : java.awt.event.FocusAdapter() {
                override fun focusLost(e: java.awt.event.FocusEvent?) {
                    service.setValue(profile, arg, text)
                    onChanged()
                }
            })
        }
        val browseButton = JButton("...").apply {
            isFocusable = false
            addActionListener {
                val chosen = choosePath(arg, project)
                if (chosen != null) {
                    field.text = chosen
                    service.setValue(profile, arg, chosen)
                    onChanged()
                }
            }
        }
        val inputPanel = JPanel(BorderLayout(4, 0)).apply {
            isOpaque = false
            add(field, BorderLayout.CENTER)
            add(browseButton, BorderLayout.EAST)
        }
        return createLabeledRow(arg.label, arg.flag, inputPanel)
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
                FileChooserDescriptorFactory.createSingleFolderDescriptor()
            else
                FileChooserDescriptorFactory.createSingleFileDescriptor()
            FileChooser.chooseFile(descriptor, project, null)?.path
        }

    private fun createIntRow(
        service: NihilArgsConfigService,
        profile: TargetProfile,
        arg: ArgDefinition,
        onChanged: () -> Unit,
    ): JComponent {
        val current = service.getValue(profile, arg).toIntOrNull() ?: arg.default.toIntOrNull() ?: 0
        val spinner = JSpinner(SpinnerNumberModel(current, arg.min ?: Int.MIN_VALUE, arg.max ?: Int.MAX_VALUE, 1)).apply {
            addChangeListener {
                service.setValue(profile, arg, value.toString())
                onChanged()
            }
        }
        return createLabeledRow(arg.label, arg.flag, spinner)
    }

    private fun createMultiRow(
        service: NihilArgsConfigService,
        profile: TargetProfile,
        arg: ArgDefinition,
        onChanged: () -> Unit,
    ): JComponent {
        val selected = service.getMultiValue(profile, arg).toMutableSet()
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
                        service.setMultiValue(profile, arg, selected.toList())
                        onChanged()
                    }
                })
            }
        }
    }
}