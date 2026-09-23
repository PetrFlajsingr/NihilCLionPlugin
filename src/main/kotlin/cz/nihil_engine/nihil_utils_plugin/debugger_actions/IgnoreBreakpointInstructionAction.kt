package cz.nihil_engine.nihil_utils_plugin.debugger_actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.xdebugger.XDebuggerManager
import cz.nihil_engine.nihil_utils_plugin.debugger.AssertBreakService

/** Debugger toolbar actions, shown while the session is stopped on a non-fatal NihilEngine assert break. */
abstract class IgnoreAssertBreakActionBase(private val persistent: Boolean) : AnAction(), DumbAware {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val session = project?.let { XDebuggerManager.getInstance(it).currentSession }
        val service = project?.let { AssertBreakService.getInstance(it) }
        val hit = service?.state(session)?.currentHit
        if (hit == null || hit.site.kind.fatal) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        e.presentation.isVisible = true
        val problem = if (persistent) service.persistentIgnoreProblem(session) else null
        e.presentation.isEnabled = problem == null
        e.presentation.description = problem ?: templatePresentation.description
        e.presentation.text = "${templatePresentation.text} ${hit.site.idText}"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val session = XDebuggerManager.getInstance(project).currentSession ?: return
        AssertBreakService.getInstance(project).ignore(session, persistent)
    }
}

/** Ignore the assert for the rest of this debug session and continue. */
class IgnoreBreakpointInstructionAction : IgnoreAssertBreakActionBase(persistent = false)

/** Ignore the assert in this and every later run (ignored_asserts.txt) and continue. */
class IgnoreAssertBreakAlwaysAction : IgnoreAssertBreakActionBase(persistent = true)
