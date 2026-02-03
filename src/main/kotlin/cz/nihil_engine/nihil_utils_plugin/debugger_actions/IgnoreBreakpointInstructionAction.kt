package cz.nihil_engine.nihil_utils_plugin.debugger_actions

//import com.intellij.openapi.actionSystem.AnAction
//import com.intellij.openapi.actionSystem.AnActionEvent
//import com.intellij.xdebugger.impl.XDebuggerManagerImpl
//import com.jetbrains.cidr.execution.debugger.CidrDebugProcess
//import cz.nihil_engine.nihil_utils_plugin.DataKeys
//
//class IgnoreBreakpointInstructionAction : AnAction (){
//
//    override fun actionPerformed(e: AnActionEvent) {
//        val debuggerManager = XDebuggerManagerImpl.getInstance(e.project ?: return)
//        val currentSession = debuggerManager.currentSession ?: return
//
//        if (!currentSession.isSuspended) {
//            return
//        }
//
//        val debugProcess = currentSession.debugProcess as? CidrDebugProcess ?: return
//        val handler = debugProcess.getUserData(DataKeys.debuggerHandler) ?: return
//        if (handler.removeBreakpointInstruction()) {
//            currentSession.resume()
//        }
//    }
//}