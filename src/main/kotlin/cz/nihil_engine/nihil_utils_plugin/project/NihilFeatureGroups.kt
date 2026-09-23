package cz.nihil_engine.nihil_utils_plugin.project

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAware

/**
 * An action group that is visible only when the project opted into at least one of [features].
 * Gating the group hides every action in it with one update().
 */
abstract class NihilFeatureGroup(private vararg val features: NihilFeature) : DefaultActionGroup(), DumbAware {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = features.any { NihilProjectConfigService.isEnabled(e.project, it) }
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

/** Nihil.RunToolbarGroup: the tool buttons and the Nihil Args button. */
class NihilRunToolbarGroup : NihilFeatureGroup(NihilFeature.TOOL_BUTTONS, NihilFeature.ARGS_POPUP)

class NihilToolButtonsGroup : NihilFeatureGroup(NihilFeature.TOOL_BUTTONS)

class NihilArgsPopupGroup : NihilFeatureGroup(NihilFeature.ARGS_POPUP)

class NihilBuildTargetSelectorGroup : NihilFeatureGroup(NihilFeature.BUILD_TARGET_SELECTOR)
