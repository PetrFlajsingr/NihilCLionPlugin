package cz.nihil_engine.nihil_utils_plugin.build_targets

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.ActionRuntimeRegistrar
import com.intellij.openapi.actionSystem.impl.ActionConfigurationCustomizer
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAware
import cz.nihil_engine.nihil_utils_plugin.project.NihilFeature
import cz.nihil_engine.nihil_utils_plugin.project.NihilProjectConfigService

/**
 * A non-popup group holding one of CLion's own CMake profile combos that hides itself in projects using the build
 * target selector. Everywhere else the toolbar renders the original action unchanged, with its own component,
 * styling and whatever CLion adds to it in later versions.
 */
class NativeProfileComboGate(native: AnAction) : DefaultActionGroup(native), DumbAware {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible =
            !NihilProjectConfigService.isEnabled(e.project, NihilFeature.BUILD_TARGET_SELECTOR)
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

/**
 * Swaps each of CLion's profile combos in [GATED] for a [NativeProfileComboGate] around the same action instance.
 * Action registrations are application-wide, so this per-project gate is the only way to hide the native combos in
 * opted-in projects without replacing the actions themselves.
 */
class NativeProfileComboCustomizer : ActionConfigurationCustomizer {

    private val log = Logger.getInstance(NativeProfileComboCustomizer::class.java)

    override fun customize(): ActionConfigurationCustomizer.CustomizeStrategy =
        object : ActionConfigurationCustomizer.LightCustomizeStrategy {
            override suspend fun customize(actionRegistrar: ActionRuntimeRegistrar) {
                for ((groupId, actionId) in GATED) gate(actionRegistrar, groupId, actionId)
            }
        }

    private fun gate(registrar: ActionRuntimeRegistrar, groupId: String, actionId: String) {
        val group = registrar.getUnstubbedAction(groupId) as? DefaultActionGroup
        val native = registrar.getUnstubbedAction(actionId)
        // The group may still hold the action's stub, so match by id rather than by instance.
        val child = group?.childActionsOrStubs?.firstOrNull { registrar.getId(it) == actionId }
        if (group == null || native == null || child == null) {
            log.warn("Cannot gate $actionId in $groupId: group=$group action=$native child=$child; the native profile combo stays visible")
            return
        }
        if (!group.replaceAction(child, NativeProfileComboGate(native))) {
            log.warn("Cannot gate $actionId in $groupId: replaceAction failed; the native profile combo stays visible")
        }
    }

    companion object {
        /** (group, action) pairs where CLion shows a CMake profile combo. */
        val GATED = listOf(
            // The execution target combo, which lists CMake profiles.
            "ExecutionTargetsToolbarGroup" to "ExecutionTargets",
            // The dedicated profile combo added in CLion 2026.3: new UI main toolbar and classic UI run toolbar.
            "ExecutionTargetsToolbarGroup" to "CLionProfiles",
            "ToolbarRunGroup" to "CLionProfiles",
        )
    }
}
