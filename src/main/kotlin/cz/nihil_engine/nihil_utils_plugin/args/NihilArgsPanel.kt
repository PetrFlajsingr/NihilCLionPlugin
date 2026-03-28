package cz.nihil_engine.nihil_utils_plugin.args

import com.intellij.execution.RunManager
import com.intellij.execution.RunManagerListener
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import cz.nihil_engine.nihil_utils_plugin.RunConfigTargetResolver
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
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

        // Listen for TOML file changes
        configChangeListener = { rebuildUI() }
        NihilArgsConfigService.getInstance(project).addChangeListener(configChangeListener)

        val connection = project.messageBus.connect(this)

        // Listen for run config selection changes
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

        // Listen for execution target changes (CMake profile switches)
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
                    contentPanel.add(createArgRow(service, profile, arg))
                    contentPanel.add(Box.createVerticalStrut(4))
                }
            }

            // Push everything to the top
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
                // Commit on Enter
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