package cz.nihil_engine.nihil_utils_plugin.args

import com.intellij.execution.RunManagerListener
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
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
import java.nio.file.Path
import javax.swing.*

class NihilArgsPanel(private val project: Project) : Disposable {

    val component: JComponent
    private val contentPanel = JPanel()
    private val configChangeListener: () -> Unit

    init {
        contentPanel.layout = BoxLayout(contentPanel, BoxLayout.Y_AXIS)
        contentPanel.border = JBUI.Borders.empty(8)

        val scrollPane = com.intellij.ui.components.JBScrollPane(contentPanel).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }
        component = scrollPane

        configChangeListener = { rebuildUI() }
        NihilArgsConfigService.getInstance(project).addChangeListener(configChangeListener)

        val connection = project.messageBus.connect(this)

        connection.subscribe(
            RunManagerListener.TOPIC,
            object : RunManagerListener {
                override fun runConfigurationSelected(settings: RunnerAndConfigurationSettings?) {
                    rebuildUI()
                }

                override fun runConfigurationChanged(settings: RunnerAndConfigurationSettings) {
                    rebuildUI()
                }
            },
        )

        connection.subscribe(
            com.intellij.execution.ExecutionTargetManager.TOPIC,
            object : com.intellij.execution.ExecutionTargetListener {
                override fun activeTargetChanged(newTarget: com.intellij.execution.ExecutionTarget) {
                    rebuildUI()
                }
            },
        )

        rebuildUI()
    }

    private fun rebuildUI() {
        ApplicationManager.getApplication().invokeLater {
            contentPanel.removeAll()

            val service = NihilArgsConfigService.getInstance(project)
            val targetName = RunConfigTargetResolver.resolve(project)
            val profile = targetName?.let { service.config.findProfile(it) }

            if (profile == null) {
                contentPanel.add(createEmptyStatePanel(targetName))
            } else {
                contentPanel.add(createProfileHeader(profile, targetName))
                contentPanel.add(Box.createVerticalStrut(8))

                for (arg in profile.args) {
                    if (arg.type == ArgType.DERIVED) continue

                    contentPanel.add(createArgRow(service, profile, arg))
                    contentPanel.add(Box.createVerticalStrut(4))
                }
            }

            contentPanel.add(Box.createVerticalGlue())

            contentPanel.revalidate()
            contentPanel.repaint()
        }
    }

    private fun createEmptyStatePanel(targetName: String?): JComponent {
        val label = if (targetName != null) {
            "No args profile matches target \"$targetName\""
        } else {
            "No run configuration selected"
        }
        return JBLabel(label).apply {
            foreground = UIUtil.getContextHelpForeground()
            border = JBUI.Borders.empty(16)
            alignmentX = Component.LEFT_ALIGNMENT
        }
    }

    private fun createProfileHeader(profile: TargetProfile, targetName: String): JComponent {
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
    ): JComponent = when (arg.type) {
        ArgType.BOOL -> createBoolRow(service, profile, arg)
        ArgType.SELECT -> createSelectRow(service, profile, arg)
        ArgType.TEXT -> createTextRow(service, profile, arg)
        ArgType.PATH -> createPathRow(service, profile, arg)
        ArgType.INT -> createIntRow(service, profile, arg)
        ArgType.MULTI -> createMultiRow(service, profile, arg)
        ArgType.DERIVED -> throw IllegalStateException("DERIVED args should not reach createArgRow")
    }

    private fun createBoolRow(
        service: NihilArgsConfigService,
        profile: TargetProfile,
        arg: ArgDefinition,
    ): JComponent {
        return JBCheckBox(arg.label, service.getBoolValue(profile, arg)).apply {
            toolTipText = arg.flag
            alignmentX = Component.LEFT_ALIGNMENT
            addActionListener {
                service.setBoolValue(profile, arg, isSelected)
            }
        }
    }

    private fun createSelectRow(
        service: NihilArgsConfigService,
        profile: TargetProfile,
        arg: ArgDefinition,
    ): JComponent {
        val current = service.getValue(profile, arg)
        val combo = ComboBox(arg.options.toTypedArray()).apply {
            selectedItem = current
            addActionListener {
                val selected = selectedItem as? String ?: return@addActionListener
                service.setValue(profile, arg, selected)
            }
        }

        return createLabeledRow(arg.label, arg.flag, combo)
    }

    private fun createTextRow(
        service: NihilArgsConfigService,
        profile: TargetProfile,
        arg: ArgDefinition,
    ): JComponent {
        val current = service.getValue(profile, arg)
        val field = JBTextField(current).apply {
            columns = 20
            addActionListener {
                service.setValue(profile, arg, text)
            }
            addFocusListener(object : java.awt.event.FocusAdapter() {
                override fun focusLost(e: java.awt.event.FocusEvent?) {
                    service.setValue(profile, arg, text)
                }
            })
        }

        return createLabeledRow(arg.label, arg.flag, field)
    }

    private fun createPathRow(
        service: NihilArgsConfigService,
        profile: TargetProfile,
        arg: ArgDefinition,
    ): JComponent {
        val field = JBTextField(service.getValue(profile, arg)).apply {
            columns = 20
            addActionListener { service.setValue(profile, arg, text) }
            addFocusListener(object : java.awt.event.FocusAdapter() {
                override fun focusLost(e: java.awt.event.FocusEvent?) {
                    service.setValue(profile, arg, text)
                }
            })
        }

        val browseButton = JButton("...").apply {
            isFocusable = false
            addActionListener {
                val chosen = browsePath(arg)
                if (chosen != null) {
                    field.text = chosen
                    service.setValue(profile, arg, chosen)
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

    private fun browsePath(arg: ArgDefinition): String? =
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
    ): JComponent {
        val current = service.getValue(profile, arg).toIntOrNull() ?: arg.default.toIntOrNull() ?: 0
        val spinner = JSpinner(SpinnerNumberModel(current, arg.min ?: Int.MIN_VALUE, arg.max ?: Int.MAX_VALUE, 1)).apply {
            addChangeListener {
                service.setValue(profile, arg, value.toString())
            }
        }
        return createLabeledRow(arg.label, arg.flag, spinner)
    }

    private fun createMultiRow(
        service: NihilArgsConfigService,
        profile: TargetProfile,
        arg: ArgDefinition,
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
                    }
                })
            }
        }
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

    override fun dispose() {
        NihilArgsConfigService.getInstance(project).removeChangeListener(configChangeListener)
    }
}