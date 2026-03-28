package cz.nihil_engine.nihil_utils_plugin.args

import com.intellij.openapi.project.Project
import cz.nihil_engine.nihil_utils_plugin.RunConfigTargetResolver
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import javax.swing.*

/**
 * Builds a panel showing the args UI for the currently active run config.
 * Intended for use inside a popup — stateless, no listeners.
 *
 * Derived args are displayed as read-only greyed-out labels that update
 * live when any editable arg in the same profile changes.
 */
object NihilArgsPanelBuilder {

    fun build(project: Project): JComponent {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8, 12)
        }

        val service = NihilArgsConfigService.getInstance(project)
        val targetName = RunConfigTargetResolver.resolve(project)
        val profile = targetName?.let { service.config.findProfile(it) }

        if (profile == null) {
            val label = if (targetName != null) {
                "No args profile matches target \"$targetName\""
            } else {
                "No run configuration selected"
            }
            panel.add(JBLabel(label).apply {
                foreground = UIUtil.getContextHelpForeground()
                alignmentX = Component.LEFT_ALIGNMENT
            })
            return panel
        }

        // Collect derived labels so we can update them when editable args change
        val derivedLabels = mutableListOf<Pair<ArgDefinition, JBLabel>>()

        val updateDerived = {
            val siblings = service.resolvedSiblingValues(profile)
            for ((arg, label) in derivedLabels) {
                label.text = service.expandMacros(arg.valueTemplate, siblings)
            }
        }

        panel.add(createHeader(profile, targetName))
        panel.add(Box.createVerticalStrut(8))

        // Editable args
        for (arg in profile.args) {
            if (arg.type == ArgType.DERIVED) continue
            panel.add(createArgRow(service, profile, arg, updateDerived))
            panel.add(Box.createVerticalStrut(4))
        }

        // Derived args section
        val derivedArgs = profile.args.filter { it.type == ArgType.DERIVED }
        if (derivedArgs.isNotEmpty()) {
            panel.add(Box.createVerticalStrut(4))
            panel.add(TitledSeparator("Derived").apply {
                alignmentX = Component.LEFT_ALIGNMENT
            })
            panel.add(Box.createVerticalStrut(4))

            val siblings = service.resolvedSiblingValues(profile)
            for (arg in derivedArgs) {
                val expanded = service.expandMacros(arg.valueTemplate, siblings)
                val valueLabel = JBLabel(expanded).apply {
                    foreground = UIUtil.getContextHelpForeground()
                }
                derivedLabels.add(arg to valueLabel)
                panel.add(createLabeledRow(arg.flag, arg.valueTemplate, valueLabel))
                panel.add(Box.createVerticalStrut(4))
            }
        }

        return panel
    }

    private fun createHeader(profile: TargetProfile, targetName: String): JComponent {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT

            add(TitledSeparator(profile.label))
            add(JBLabel(targetName).apply {
                foreground = UIUtil.getContextHelpForeground()
                font = JBUI.Fonts.smallFont()
                border = JBUI.Borders.emptyLeft(4)
            })
        }
    }

    private fun createArgRow(
        service: NihilArgsConfigService,
        profile: TargetProfile,
        arg: ArgDefinition,
        onChanged: () -> Unit,
    ): JComponent = when (arg.type) {
        ArgType.BOOL -> createBoolRow(service, profile, arg, onChanged)
        ArgType.SELECT -> createSelectRow(service, profile, arg, onChanged)
        ArgType.TEXT -> createTextRow(service, profile, arg, onChanged)
        ArgType.DERIVED -> throw IllegalStateException("DERIVED args should not reach createArgRow")
    }

    private fun createBoolRow(
        service: NihilArgsConfigService,
        profile: TargetProfile,
        arg: ArgDefinition,
        onChanged: () -> Unit,
    ): JComponent {
        return JBCheckBox(arg.label, service.getBoolValue(profile, arg)).apply {
            toolTipText = arg.flag
            alignmentX = Component.LEFT_ALIGNMENT
            addActionListener {
                service.setBoolValue(profile, arg, isSelected)
                onChanged()
            }
        }
    }

    private fun createSelectRow(
        service: NihilArgsConfigService,
        profile: TargetProfile,
        arg: ArgDefinition,
        onChanged: () -> Unit,
    ): JComponent {
        val current = service.getValue(profile, arg)
        val combo = ComboBox(arg.options.toTypedArray()).apply {
            selectedItem = current
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
        val current = service.getValue(profile, arg)
        val field = JBTextField(current).apply {
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

    private fun createLabeledRow(
        label: String,
        tooltip: String,
        control: JComponent,
    ): JComponent {
        return JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height + 8)

            val labelComponent = JBLabel("$label:").apply {
                toolTipText = tooltip
                preferredSize = Dimension(140, preferredSize.height)
            }
            add(labelComponent, BorderLayout.WEST)
            add(control, BorderLayout.CENTER)
        }
    }
}