package cz.nihil_engine.nihil_utils_plugin.activities

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import cz.nihil_engine.nihil_utils_plugin.asserts.IgnoredAssertGutter
import cz.nihil_engine.nihil_utils_plugin.cvars.CVarGutter
import cz.nihil_engine.nihil_utils_plugin.feature_flags.FeatureFlagLens

class MainActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        logExecutionTargetsRegistration()
        IgnoredAssertGutter.getInstance(project).start()
        FeatureFlagLens.getInstance(project).start()
        CVarGutter.getInstance(project).start()
    }

    private fun logExecutionTargetsRegistration() {
        val actionManager = ActionManager.getInstance()
        val action = actionManager.getAction("ExecutionTargets")
        val group = actionManager.getAction("ExecutionTargetsToolbarGroup") as? DefaultActionGroup
        val children = group?.getChildren(actionManager)?.joinToString {
            (actionManager.getId(it) ?: it.javaClass.simpleName) +
                ((it as? DefaultActionGroup)?.getChildren(actionManager)?.joinToString(prefix = "{", postfix = "}") { c -> c.javaClass.simpleName } ?: "")
        }
        Logger.getInstance(MainActivity::class.java).info(
            "ExecutionTargets -> ${action?.javaClass?.name}; ExecutionTargetsToolbarGroup children: [$children]"
        )
    }
}
