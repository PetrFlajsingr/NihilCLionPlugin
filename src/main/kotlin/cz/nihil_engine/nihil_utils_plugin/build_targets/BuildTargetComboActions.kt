package cz.nihil_engine.nihil_utils_plugin.build_targets

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.KeepPopupOnPerform
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Condition
import cz.nihil_engine.nihil_utils_plugin.project.NihilFeature
import cz.nihil_engine.nihil_utils_plugin.project.NihilProjectConfigService
import javax.swing.Icon
import javax.swing.JComponent

abstract class BuildTargetComboBase : ComboBoxAction(), DumbAware {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun shouldShowDisabledActions() = true

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (!NihilProjectConfigService.isEnabled(project, NihilFeature.BUILD_TARGET_SELECTOR)) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        e.presentation.isVisible = true
        update(e, BuildTargetService.getInstance(project!!).current())
    }

    protected abstract fun update(e: AnActionEvent, state: BuildTargetState)

    override fun createPopupActionGroup(button: JComponent, dataContext: DataContext): DefaultActionGroup {
        val project = dataContext.getData(CommonDataKeys.PROJECT) ?: return DefaultActionGroup()
        return DefaultActionGroup().apply { fill(this, project, BuildTargetService.getInstance(project).current()) }
    }

    protected abstract fun fill(group: DefaultActionGroup, project: Project, state: BuildTargetState)

    override fun getPreselectCondition(): Condition<AnAction> = Condition { (it as? ChoiceAction)?.selected == true }

    protected fun addWarnings(group: DefaultActionGroup, warnings: List<GridWarning>, problems: List<String>) {
        if (warnings.isEmpty() && problems.isEmpty()) return
        group.add(Separator.create("Warnings"))
        problems.forEach { group.add(InfoAction(it, AllIcons.General.Warning)) }
        warnings.forEach { group.add(InfoAction("${it.displayName}: ${it.message}", AllIcons.General.Warning)) }
    }

    /** Submenu with a checkbox per CMake profile; toggling enables or disables it like CLion's CMake settings. */
    protected fun addProfileToggles(group: DefaultActionGroup, grid: BuildTargetGrid) {
        if (grid.profiles.isEmpty()) return
        group.add(Separator.create())
        group.add(DefaultActionGroup("Enable / Disable Profiles", true).apply {
            grid.profiles.forEach { add(ProfileToggleAction(it.name, it.displayName)) }
        })
    }

    private class ProfileToggleAction(private val profileName: String, private val label: String) : ToggleAction(), DumbAware {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT

        override fun update(e: AnActionEvent) {
            super.update(e)
            e.presentation.setText(label, false)
            e.presentation.putClientProperty(ActionUtil.SECONDARY_TEXT, profileName.takeIf { it != label })
            e.presentation.keepPopupOnPerform = KeepPopupOnPerform.Always
            val project = e.project ?: return
            e.presentation.isEnabled = BuildTargetService.getInstance(project).canToggle(profileName)
        }

        override fun isSelected(e: AnActionEvent): Boolean {
            val project = e.project ?: return false
            return BuildTargetService.getInstance(project).isEnabled(profileName)
        }

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            val project = e.project ?: return
            if (isSelected(e) != state) BuildTargetService.getInstance(project).toggleEnabled(profileName)
        }
    }

    protected class ChoiceAction(
        private val label: String,
        private val secondary: String?,
        val selected: Boolean,
        private val isChoosable: Boolean,
        private val onChoose: () -> Unit,
    ) : AnAction(), DumbAware {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT

        override fun update(e: AnActionEvent) {
            e.presentation.setText(label, false)
            e.presentation.isEnabled = isChoosable
            e.presentation.icon = if (selected) AllIcons.Actions.Checked else null
            e.presentation.putClientProperty(ActionUtil.SECONDARY_TEXT, secondary)
        }

        override fun actionPerformed(e: AnActionEvent) = onChoose()
    }

    /** A disabled row that only carries information. */
    protected class InfoAction(private val label: String, private val icon: Icon?) : AnAction(), DumbAware {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT

        override fun update(e: AnActionEvent) {
            e.presentation.setText(label, false)
            e.presentation.isEnabled = false
            e.presentation.icon = icon
        }

        override fun actionPerformed(e: AnActionEvent) {}
    }
}

/** Combo 1: the build variant ("Development", "Development 26", "Test (debug)", ...). */
class BuildVariantComboAction : BuildTargetComboBase() {

    override fun update(e: AnActionEvent, state: BuildTargetState) {
        val p = e.presentation
        p.isEnabled = state.grid.variants.isNotEmpty()
        p.setText(state.entry?.variant ?: state.profileName?.let(state.grid::displayName) ?: "No CMake profile", false)
        val hasWarning = state.profileName != null &&
            (state.entry == null || state.grid.warningsFor(state.profileName).isNotEmpty())
        p.icon = if (hasWarning || state.configProblems.isNotEmpty()) AllIcons.General.Warning else null
        p.description = when {
            state.profileName == null -> "Build variant"
            else -> "Build variant (profile \"${state.grid.displayName(state.profileName)}\")"
        }
    }

    override fun fill(group: DefaultActionGroup, project: Project, state: BuildTargetState) {
        val grid = state.grid
        val currentTarget = state.entry?.target
        val (usable, disabledOnly) = grid.variants.partition { grid.hasEnabledProfile(it) }

        for (variant in usable) {
            group.add(ChoiceAction(
                label = variant,
                secondary = grid.enabledTargets(variant).joinToString(" · "),
                selected = variant == state.entry?.variant,
                isChoosable = true,
            ) {
                val target = grid.targetAfterVariantSwitch(variant, currentTarget) ?: return@ChoiceAction
                grid.entry(variant, target)?.let { BuildTargetService.getInstance(project).select(it) }
            })
        }
        if (disabledOnly.isNotEmpty()) {
            group.add(Separator.create())
            disabledOnly.forEach {
                group.add(ChoiceAction(it, "disabled profile", selected = false, isChoosable = false) {})
            }
        }
        addProfileToggles(group, grid)
        addWarnings(group, grid.warnings, state.configProblems)
    }
}

/** Combo 2: the build target. Always lists every configured target; missing pairs are disabled with the reason. */
class BuildTargetComboAction : BuildTargetComboBase() {

    override fun update(e: AnActionEvent, state: BuildTargetState) {
        val p = e.presentation
        p.isEnabled = state.entry != null
        p.setText(state.entry?.target ?: "—", false)
        p.icon = null
        p.description = "Build target"
    }

    override fun fill(group: DefaultActionGroup, project: Project, state: BuildTargetState) {
        val grid = state.grid
        val variant = state.entry?.variant ?: return
        for (target in grid.targets) {
            val entry = grid.entry(variant, target)
            val secondary = when {
                entry == null -> "no \"${grid.expectedProfileName(variant, target)}\" profile"
                !entry.enabled -> "\"${entry.displayName}\" is disabled"
                else -> entry.displayName
            }
            group.add(ChoiceAction(
                label = target,
                secondary = secondary,
                selected = target == state.entry.target,
                isChoosable = entry?.enabled == true,
            ) {
                entry?.let { BuildTargetService.getInstance(project).select(it) }
            })
        }
        val relevant = grid.warnings.filter { w -> grid.targets.any { grid.entry(variant, it)?.profileName == w.profileName } }
        addWarnings(group, relevant, emptyList())
    }
}
