package cz.nihil_engine.nihil_utils_plugin.args

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.Balloon.Position
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.TitledSeparator
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import cz.nihil_engine.nihil_utils_plugin.RunConfigTargetResolver
import cz.nihil_engine.nihil_utils_plugin.config.RunConfigExtractor
import java.awt.BorderLayout
import java.awt.Component
import java.awt.datatransfer.StringSelection
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Builds a panel showing the args UI for the currently active run config, for use inside a popup.
 */
object NihilArgsPanelBuilder {

    fun build(project: Project, onLayoutChanged: () -> Unit = {}): JComponent {
        val root = JPanel(BorderLayout())
        lateinit var rebuild: () -> Unit
        rebuild = {
            root.removeAll()
            root.add(content(project, rebuild, onLayoutChanged), BorderLayout.CENTER)
            root.revalidate()
            root.repaint()
            onLayoutChanged()
        }
        root.add(content(project, rebuild, onLayoutChanged), BorderLayout.CENTER)
        return root
    }

    private fun content(project: Project, rebuild: () -> Unit, onLayoutChanged: () -> Unit): JComponent {
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

        val ctx = ArgsPanelContext(project, service, profile, targetName) {
            panel.revalidate()
            onLayoutChanged()
        }

        panel.add(createHeader(profile, targetName))
        panel.add(Box.createVerticalStrut(8))
        panel.add(createPresetBar(ctx, rebuild))
        panel.add(Box.createVerticalStrut(8))

        for (arg in profile.args) {
            if (arg.type == ArgType.DERIVED) continue
            panel.add(NihilArgRowFactory.createArgRow(ctx, arg))
            panel.add(Box.createVerticalStrut(4))
        }

        val derivedArgs = profile.args.filter { it.type == ArgType.DERIVED }
        if (derivedArgs.isNotEmpty()) {
            panel.add(Box.createVerticalStrut(4))
            panel.add(TitledSeparator("Derived").apply {
                alignmentX = Component.LEFT_ALIGNMENT
            })
            panel.add(Box.createVerticalStrut(4))

            val resolved = ctx.resolved()
            for (arg in derivedArgs) {
                val valueLabel = JBLabel(resolved[arg.key]?.value.orEmpty()).apply {
                    foreground = UIUtil.getContextHelpForeground()
                }
                ctx.onChange { valueLabel.text = it[arg.key]?.value.orEmpty() }
                panel.add(NihilArgRowFactory.createLabeledRow(arg.flag, arg.valueTemplate, valueLabel))
                panel.add(Box.createVerticalStrut(4))
            }
        }

        panel.add(Box.createVerticalStrut(8))
        panel.add(createActionButtons(project, service, targetName))

        return panel
    }

    private sealed interface PresetItem {
        data object Custom : PresetItem
        data class Choice(val choice: PresetChoice) : PresetItem
    }

    /**
     * Preset combo.
     */
    private fun createPresetBar(ctx: ArgsPanelContext, rebuild: () -> Unit): JComponent {
        val combo = ComboBox<PresetItem>().apply {
            renderer = SimpleListCellRenderer.create { label, value, _ ->
                when (value) {
                    is PresetItem.Choice -> {
                        label.text = value.choice.preset.label + if (value.choice.personal) "  (personal)" else ""
                    }
                    PresetItem.Custom, null -> {
                        label.text = "Custom"
                        label.foreground = UIUtil.getContextHelpForeground()
                    }
                }
            }
        }
        val delete = JButton("Delete").apply { toolTipText = "Delete this personal preset" }
        var updating = false

        fun refresh() {
            updating = true
            val choices = ctx.service.presets(ctx.profile)
            val match = choices.firstOrNull { ctx.service.matches(ctx.profile, it.preset) }
            val items = listOfNotNull(if (match == null) PresetItem.Custom else null) + choices.map { PresetItem.Choice(it) }
            combo.model = DefaultComboBoxModel(items.toTypedArray())
            combo.selectedItem = match?.let { PresetItem.Choice(it) } ?: PresetItem.Custom
            combo.isEnabled = choices.isNotEmpty()
            delete.isEnabled = match?.personal == true
            updating = false
        }
        refresh()
        ctx.onChange { refresh() }

        combo.addActionListener {
            if (updating) return@addActionListener
            val choice = (combo.selectedItem as? PresetItem.Choice)?.choice ?: return@addActionListener
            ctx.service.applyValues(ctx.profile, choice.preset.values)
            rebuild()
        }

        val save = JButton("Save…").apply {
            toolTipText = "Save the current values as a personal preset"
            addActionListener {
                val current = (combo.selectedItem as? PresetItem.Choice)?.choice?.takeIf { it.personal }?.preset?.label
                val name = Messages.showInputDialog(
                    ctx.project, "Save the current values as a personal preset:", "Save Args Preset", null,
                    current.orEmpty(), NonBlankValidator,
                ) ?: return@addActionListener
                NihilArgsPresetStore.getInstance(ctx.project).save(ctx.profile.key, name.trim(), ctx.service.currentValues(ctx.profile))
                refresh()
            }
        }
        delete.addActionListener {
            val choice = (combo.selectedItem as? PresetItem.Choice)?.choice?.takeIf { it.personal } ?: return@addActionListener
            NihilArgsPresetStore.getInstance(ctx.project).delete(ctx.profile.key, choice.preset.key)
            refresh()
        }

        val controls = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(combo)
            add(Box.createHorizontalStrut(4))
            add(save)
            add(Box.createHorizontalStrut(4))
            add(delete)
        }
        return NihilArgRowFactory.createLabeledRow(
            "Preset", "Team presets come from nihil_args.toml; personal ones are saved here", controls,
        )
    }

    private object NonBlankValidator : InputValidator {
        override fun checkInput(inputString: String?) = !inputString.isNullOrBlank()
        override fun canClose(inputString: String?) = checkInput(inputString)
    }

    private fun createActionButtons(
        project: Project,
        service: NihilArgsConfigService,
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
