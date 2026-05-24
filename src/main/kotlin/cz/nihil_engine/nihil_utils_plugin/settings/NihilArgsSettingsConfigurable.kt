package cz.nihil_engine.nihil_utils_plugin.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import cz.nihil_engine.nihil_utils_plugin.args.ArgDefinition
import cz.nihil_engine.nihil_utils_plugin.args.ArgType
import cz.nihil_engine.nihil_utils_plugin.args.NihilArgsConfig
import cz.nihil_engine.nihil_utils_plugin.args.NihilArgsConfigService
import cz.nihil_engine.nihil_utils_plugin.args.PathDirection
import cz.nihil_engine.nihil_utils_plugin.args.PathKind
import cz.nihil_engine.nihil_utils_plugin.args.TargetProfile
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.ListSelectionModel
import javax.swing.SpinnerNumberModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class NihilArgsSettingsConfigurable(private val project: Project) : Configurable {

    private var rootPanel: JPanel? = null
    private var workingProfiles: MutableList<WorkingProfile> = mutableListOf()
    private var originalProfiles: List<WorkingProfile> = emptyList()

    private val profileListModel = DefaultListModel<WorkingProfile>()
    private val profileList = JBList(profileListModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = profileRenderer()
    }

    private val argListModel = DefaultListModel<WorkingArg>()
    private val argList = JBList(argListModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = argRenderer()
    }

    private val profileDetailHost = JPanel(BorderLayout())
    private val argDetailHost = JPanel(BorderLayout())

    private var suppressEvents = false

    override fun getDisplayName(): String = "Nihil Args"

    override fun createComponent(): JComponent {
        loadFromService()

        val left = ToolbarDecorator.createDecorator(profileList)
            .setAddAction { addProfile() }
            .setRemoveAction { removeProfile() }
            .createPanel()
            .apply { preferredSize = Dimension(220, 0) }

        val right = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyLeft(8)
            add(profileDetailHost, BorderLayout.CENTER)
        }

        profileList.addListSelectionListener {
            if (it.valueIsAdjusting) return@addListSelectionListener
            renderProfileDetail()
        }
        argList.addListSelectionListener {
            if (it.valueIsAdjusting) return@addListSelectionListener
            renderArgDetail()
        }

        val root = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
            add(left, BorderLayout.WEST)
            add(right, BorderLayout.CENTER)
            preferredSize = Dimension(720, 520)
        }
        rootPanel = root

        if (profileListModel.size() > 0) profileList.selectedIndex = 0
        renderProfileDetail()

        return root
    }

    override fun isModified(): Boolean = workingProfiles != originalProfiles

    @Throws(ConfigurationException::class)
    override fun apply() {
        validate()
        val newConfig = toConfig(workingProfiles)
        NihilArgsConfigService.getInstance(project).save(newConfig)
        originalProfiles = workingProfiles.map { it.deepCopy() }
    }

    override fun reset() {
        loadFromService()
        if (profileListModel.size() > 0) profileList.selectedIndex = 0
        renderProfileDetail()
    }

    override fun disposeUIResources() {
        rootPanel = null
    }

    private fun loadFromService() {
        val service = NihilArgsConfigService.getInstance(project)
        workingProfiles = service.config.profiles.map { WorkingProfile.from(it) }.toMutableList()
        originalProfiles = workingProfiles.map { it.deepCopy() }

        suppressEvents = true
        profileListModel.clear()
        workingProfiles.forEach { profileListModel.addElement(it) }
        suppressEvents = false
    }

    @Throws(ConfigurationException::class)
    private fun validate() {
        val seenProfileKeys = mutableSetOf<String>()
        for (profile in workingProfiles) {
            if (profile.key.isBlank()) {
                throw ConfigurationException("Profile key cannot be empty")
            }
            if (!seenProfileKeys.add(profile.key)) {
                throw ConfigurationException("Duplicate profile key: ${profile.key}")
            }
            try {
                profile.filterPattern.toRegex()
            } catch (e: Exception) {
                throw ConfigurationException("Profile '${profile.key}' has invalid filter regex: ${e.message}")
            }
            val seenArgKeys = mutableSetOf<String>()
            for (arg in profile.args) {
                if (arg.key.isBlank()) {
                    throw ConfigurationException("Profile '${profile.key}' has an arg with an empty key")
                }
                if (!seenArgKeys.add(arg.key)) {
                    throw ConfigurationException("Profile '${profile.key}' has duplicate arg key: ${arg.key}")
                }
                if (arg.flag.isBlank()) {
                    throw ConfigurationException("Arg '${profile.key}.${arg.key}' has an empty flag")
                }
            }
        }
    }

    // ---------- Profile list operations ----------

    private fun addProfile() {
        val newProfile = WorkingProfile(
            key = nextKey("profile", workingProfiles.map { it.key }.toSet()),
            label = "New Profile",
            filterPattern = ".*",
            args = mutableListOf(),
        )
        workingProfiles.add(newProfile)
        profileListModel.addElement(newProfile)
        profileList.selectedIndex = profileListModel.size() - 1
    }

    private fun removeProfile() {
        val idx = profileList.selectedIndex
        if (idx < 0) return
        workingProfiles.removeAt(idx)
        profileListModel.remove(idx)
        if (profileListModel.size() > 0) {
            profileList.selectedIndex = (idx).coerceAtMost(profileListModel.size() - 1)
        } else {
            renderProfileDetail()
        }
    }

    private fun renderProfileDetail() {
        profileDetailHost.removeAll()
        argListModel.clear()

        val profile = profileList.selectedValue
        if (profile == null) {
            profileDetailHost.add(emptyHint("Select or add a profile on the left"))
            profileDetailHost.revalidate()
            profileDetailHost.repaint()
            renderArgDetail()
            return
        }

        profile.args.forEach { argListModel.addElement(it) }

        val detail = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(2, 0, 2, 6)
        }

        val keyField = textField(profile.key) { value ->
            profile.key = value
            profileList.repaint()
        }
        val labelField = textField(profile.label) { value ->
            profile.label = value
            profileList.repaint()
        }
        val filterField = textField(profile.filterPattern) { value ->
            profile.filterPattern = value
        }

        addLabeledRow(detail, gbc, 0, "Key:", keyField)
        addLabeledRow(detail, gbc, 1, "Label:", labelField)
        addLabeledRow(detail, gbc, 2, "Filter (regex):", filterField)

        val argsToolbar = ToolbarDecorator.createDecorator(argList)
            .setAddAction { addArg(profile) }
            .setRemoveAction { removeArg(profile) }
            .setMoveUpAction { moveArg(profile, -1) }
            .setMoveDownAction { moveArg(profile, +1) }
            .createPanel()

        val argsListPanel = JPanel(BorderLayout()).apply {
            preferredSize = Dimension(220, 200)
            add(argsToolbar, BorderLayout.CENTER)
        }

        val argDetailScroll = JBScrollPane(argDetailHost).apply {
            border = BorderFactory.createEmptyBorder()
        }

        val argsRow = JPanel(BorderLayout(8, 0)).apply {
            add(argsListPanel, BorderLayout.WEST)
            add(argDetailScroll, BorderLayout.CENTER)
        }

        val wrapper = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(detail.alignLeft())
            add(TitledSeparator("Args").alignLeft())
            add(argsRow.alignLeft())
        }

        profileDetailHost.add(wrapper)
        profileDetailHost.revalidate()
        profileDetailHost.repaint()

        if (argListModel.size() > 0) argList.selectedIndex = 0
        renderArgDetail()
    }

    private fun addArg(profile: WorkingProfile) {
        val arg = WorkingArg(
            key = nextKey("arg", profile.args.map { it.key }.toSet()),
            label = "New Arg",
            type = ArgType.BOOL,
            flag = "--new-arg",
            default = "false",
            options = mutableListOf(),
            valueTemplate = "",
            pathKind = PathKind.FILE,
            pathDirection = PathDirection.INPUT,
            min = null,
            max = null,
            separator = ",",
        )
        profile.args.add(arg)
        argListModel.addElement(arg)
        argList.selectedIndex = argListModel.size() - 1
    }

    private fun removeArg(profile: WorkingProfile) {
        val idx = argList.selectedIndex
        if (idx < 0) return
        profile.args.removeAt(idx)
        argListModel.remove(idx)
        if (argListModel.size() > 0) {
            argList.selectedIndex = idx.coerceAtMost(argListModel.size() - 1)
        } else {
            renderArgDetail()
        }
    }

    private fun moveArg(profile: WorkingProfile, delta: Int) {
        val idx = argList.selectedIndex
        val newIdx = idx + delta
        if (idx < 0 || newIdx < 0 || newIdx >= profile.args.size) return
        val item = profile.args.removeAt(idx)
        profile.args.add(newIdx, item)
        argListModel.remove(idx)
        argListModel.add(newIdx, item)
        argList.selectedIndex = newIdx
    }

    private fun renderArgDetail() {
        argDetailHost.removeAll()
        val arg = argList.selectedValue
        if (arg == null) {
            argDetailHost.add(emptyHint("Select an arg to edit its details"))
            argDetailHost.revalidate()
            argDetailHost.repaint()
            return
        }
        argDetailHost.add(buildArgDetail(arg))
        argDetailHost.revalidate()
        argDetailHost.repaint()
    }

    private fun buildArgDetail(arg: WorkingArg): JComponent {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(2, 0, 2, 6)
        }
        var row = 0

        val keyField = textField(arg.key) { value ->
            arg.key = value
            argList.repaint()
        }
        val labelField = textField(arg.label) { arg.label = it }
        val flagField = textField(arg.flag) { arg.flag = it }

        val typeCombo = ComboBox(ArgType.entries.toTypedArray()).apply {
            selectedItem = arg.type
            addActionListener {
                val newType = selectedItem as ArgType
                if (newType != arg.type) {
                    arg.type = newType
                    arg.default = defaultForType(newType)
                    argList.repaint()
                    renderArgDetail()
                }
            }
        }

        addLabeledRow(panel, gbc, row++, "Key:", keyField)
        addLabeledRow(panel, gbc, row++, "Label:", labelField)
        addLabeledRow(panel, gbc, row++, "Type:", typeCombo)
        addLabeledRow(panel, gbc, row++, "Flag:", flagField)

        // Type-specific fields
        when (arg.type) {
            ArgType.BOOL -> {
                val combo = ComboBox(arrayOf("false", "true")).apply {
                    selectedItem = if (arg.default.equals("true", ignoreCase = true)) "true" else "false"
                    addActionListener { arg.default = selectedItem as String }
                }
                addLabeledRow(panel, gbc, row++, "Default:", combo)
            }
            ArgType.SELECT -> {
                val defaultField = textField(arg.default) { arg.default = it }
                val optionsField = textField(arg.options.joinToString(", ")) { value ->
                    arg.options = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
                }
                addLabeledRow(panel, gbc, row++, "Default:", defaultField)
                addLabeledRow(panel, gbc, row++, "Options (comma-sep):", optionsField)
            }
            ArgType.TEXT -> {
                val defaultField = textField(arg.default) { arg.default = it }
                addLabeledRow(panel, gbc, row++, "Default:", defaultField)
            }
            ArgType.PATH -> {
                val defaultField = textField(arg.default) { arg.default = it }
                val kindCombo = ComboBox(PathKind.entries.toTypedArray()).apply {
                    selectedItem = arg.pathKind
                    addActionListener { arg.pathKind = selectedItem as PathKind }
                }
                val dirCombo = ComboBox(PathDirection.entries.toTypedArray()).apply {
                    selectedItem = arg.pathDirection
                    addActionListener { arg.pathDirection = selectedItem as PathDirection }
                }
                addLabeledRow(panel, gbc, row++, "Default:", defaultField)
                addLabeledRow(panel, gbc, row++, "Path kind:", kindCombo)
                addLabeledRow(panel, gbc, row++, "Path direction:", dirCombo)
            }
            ArgType.INT -> {
                val defaultField = textField(arg.default) { arg.default = it }
                val minField = textField(arg.min?.toString() ?: "") { value ->
                    arg.min = value.toIntOrNull()
                }
                val maxField = textField(arg.max?.toString() ?: "") { value ->
                    arg.max = value.toIntOrNull()
                }
                addLabeledRow(panel, gbc, row++, "Default:", defaultField)
                addLabeledRow(panel, gbc, row++, "Min (blank = unset):", minField)
                addLabeledRow(panel, gbc, row++, "Max (blank = unset):", maxField)
            }
            ArgType.MULTI -> {
                val defaultField = textField(arg.default) { arg.default = it }
                val optionsField = textField(arg.options.joinToString(", ")) { value ->
                    arg.options = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
                }
                val separatorField = textField(arg.separator) { value ->
                    arg.separator = if (value.isEmpty()) "," else value
                }
                addLabeledRow(panel, gbc, row++, "Default:", defaultField)
                addLabeledRow(panel, gbc, row++, "Options (comma-sep):", optionsField)
                addLabeledRow(panel, gbc, row++, "CLI separator:", separatorField)
            }
            ArgType.DERIVED -> {
                val templateField = textField(arg.valueTemplate) { arg.valueTemplate = it }
                addLabeledRow(panel, gbc, row++, "Value template:", templateField)
                val hint = JBLabel("Use \${PROJECT_DIR} or \${other_arg_key} placeholders.").apply {
                    foreground = UIUtil.getContextHelpForeground()
                    font = JBUI.Fonts.smallFont()
                }
                gbc.gridx = 1
                gbc.gridy = row++
                gbc.weightx = 1.0
                panel.add(hint, gbc)
            }
        }

        // Filler to push fields to the top.
        gbc.gridx = 0
        gbc.gridy = row
        gbc.gridwidth = 2
        gbc.weighty = 1.0
        gbc.fill = GridBagConstraints.BOTH
        panel.add(JPanel().apply { isOpaque = false }, gbc)

        return panel
    }

    private fun defaultForType(type: ArgType): String = when (type) {
        ArgType.BOOL -> "false"
        ArgType.INT -> "0"
        else -> ""
    }

    private fun textField(initial: String, onChange: (String) -> Unit): JBTextField {
        val field = JBTextField(initial)
        field.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = fire()
            override fun removeUpdate(e: DocumentEvent?) = fire()
            override fun changedUpdate(e: DocumentEvent?) = fire()
            private fun fire() {
                if (suppressEvents) return
                onChange(field.text)
            }
        })
        return field
    }

    private fun addLabeledRow(
        panel: JPanel,
        gbc: GridBagConstraints,
        row: Int,
        label: String,
        control: JComponent,
    ) {
        gbc.gridx = 0
        gbc.gridy = row
        gbc.weightx = 0.0
        gbc.gridwidth = 1
        panel.add(JBLabel(label).apply { preferredSize = Dimension(140, preferredSize.height) }, gbc)

        gbc.gridx = 1
        gbc.gridy = row
        gbc.weightx = 1.0
        panel.add(control, gbc)
    }

    private fun emptyHint(text: String): JComponent = JBLabel(text).apply {
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.empty(16)
    }

    private fun nextKey(prefix: String, taken: Set<String>): String {
        var i = 1
        while ("${prefix}_$i" in taken) i++
        return "${prefix}_$i"
    }

    private fun <T : JComponent> T.alignLeft(): T = apply {
        alignmentX = Component.LEFT_ALIGNMENT
    }

    private fun profileRenderer() = object : javax.swing.DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: javax.swing.JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
            val profile = value as? WorkingProfile
            c.text = profile?.let {
                val labelPart = if (it.label.isBlank()) it.key else it.label
                "$labelPart  (${it.key})"
            } ?: ""
            return c
        }
    }

    private fun argRenderer() = object : javax.swing.DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: javax.swing.JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
            val arg = value as? WorkingArg
            c.text = arg?.let { "${it.key}  [${it.type.name.lowercase()}]" } ?: ""
            return c
        }
    }

    private fun toConfig(profiles: List<WorkingProfile>): NihilArgsConfig =
        NihilArgsConfig(profiles.map { it.toProfile() })

    private data class WorkingProfile(
        var key: String,
        var label: String,
        var filterPattern: String,
        val args: MutableList<WorkingArg>,
    ) {
        fun deepCopy(): WorkingProfile = copy(args = args.map { it.copy(options = it.options.toMutableList()) }.toMutableList())

        fun toProfile(): TargetProfile = TargetProfile(
            key = key,
            label = label,
            filter = filterPattern.toRegex(),
            args = args.map { it.toArg() },
        )

        companion object {
            fun from(p: TargetProfile): WorkingProfile = WorkingProfile(
                key = p.key,
                label = p.label,
                filterPattern = p.filter.pattern,
                args = p.args.map { WorkingArg.from(it) }.toMutableList(),
            )
        }
    }

    private data class WorkingArg(
        var key: String,
        var label: String,
        var type: ArgType,
        var flag: String,
        var default: String,
        var options: MutableList<String>,
        var valueTemplate: String,
        var pathKind: PathKind,
        var pathDirection: PathDirection,
        var min: Int?,
        var max: Int?,
        var separator: String,
    ) {
        fun toArg(): ArgDefinition = ArgDefinition(
            key = key,
            label = label,
            type = type,
            flag = flag,
            default = default,
            options = options.toList(),
            valueTemplate = valueTemplate,
            pathKind = pathKind,
            pathDirection = pathDirection,
            min = min,
            max = max,
            separator = separator,
        )

        companion object {
            fun from(a: ArgDefinition): WorkingArg = WorkingArg(
                key = a.key,
                label = a.label,
                type = a.type,
                flag = a.flag,
                default = a.default,
                options = a.options.toMutableList(),
                valueTemplate = a.valueTemplate,
                pathKind = a.pathKind,
                pathDirection = a.pathDirection,
                min = a.min,
                max = a.max,
                separator = a.separator,
            )
        }
    }
}
