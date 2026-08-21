package cz.nihil_engine.nihil_utils_plugin.args

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.Balloon.Position
import cz.nihil_engine.nihil_utils_plugin.RunConfigTargetResolver
import cz.nihil_engine.nihil_utils_plugin.config.RunConfigExtractor
import com.intellij.ui.TitledSeparator
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.datatransfer.StringSelection
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

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

        for (arg in profile.args) {
            if (arg.type == ArgType.DERIVED) continue
            panel.add(NihilArgRowFactory.createArgRow(service, profile, arg, project, updateDerived))
            panel.add(Box.createVerticalStrut(4))
        }

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
                panel.add(NihilArgRowFactory.createLabeledRow(arg.flag, arg.valueTemplate, valueLabel))
                panel.add(Box.createVerticalStrut(4))
            }
        }

        panel.add(Box.createVerticalStrut(8))
        panel.add(createActionButtons(project, service, profile, targetName))

        return panel
    }

    private fun createActionButtons(
        project: Project,
        service: NihilArgsConfigService,
        profile: TargetProfile,
        targetName: String,
    ): JComponent {
        val copyArgs = JButton("Copy Args").apply {
            toolTipText = "Copy the command-line arguments to the clipboard"
            addActionListener {
                val args = resolveArgs(project, service, targetName)
                if (args.isBlank()) {
                    showBalloon(this, "No arguments to copy", MessageType.WARNING)
                } else {
                    copyToClipboard(args)
                    showBalloon(this, "Arguments copied", MessageType.INFO)
                }
            }
        }

        val copyExeAndArgs = JButton("Copy Exe + Args").apply {
            toolTipText = "Copy the absolute executable path followed by the arguments"
            addActionListener {
                val data = RunConfigExtractor.extract(project)
                if (data == null || data.exePath.isBlank()) {
                    showBalloon(this, "Could not resolve executable path", MessageType.WARNING)
                } else {
                    val text = if (data.args.isBlank()) data.exePath else "${data.exePath} ${data.args}"
                    copyToClipboard(text)
                    showBalloon(this, "Executable + arguments copied", MessageType.INFO)
                }
            }
        }

        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(copyArgs)
            add(Box.createHorizontalStrut(8))
            add(copyExeAndArgs)
            add(Box.createHorizontalGlue())
        }
    }

    private fun resolveArgs(
        project: Project,
        service: NihilArgsConfigService,
        targetName: String,
    ): String {
        RunConfigExtractor.extract(project)?.let { return it.args }
        return service.buildCommandLineArgs(targetName).joinToString(" ")
    }

    private fun copyToClipboard(text: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(text))
    }

    private fun showBalloon(anchor: JComponent, message: String, type: MessageType) {
        val balloon = JBPopupFactory.getInstance()
            .createHtmlTextBalloonBuilder(message, type, null)
            .setFadeoutTime(2000)
            .createBalloon()
        balloon.show(RelativePoint.getCenterOf(anchor), Position.above)
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
}