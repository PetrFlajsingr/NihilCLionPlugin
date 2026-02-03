package cz.nihil_engine.nihil_utils_plugin.activities

//import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
//import com.intellij.openapi.util.Disposer
//import com.intellij.xdebugger.XDebugProcess
//import com.intellij.xdebugger.XDebuggerManager
//import com.intellij.xdebugger.XDebuggerManagerListener
//import com.jetbrains.cidr.execution.debugger.CidrDebugProcess
//import com.jetbrains.cidr.execution.debugger.backend.gdb.GDBDriver
//import com.jetbrains.cidr.execution.debugger.backend.lldb.LLDBDriver
//import cz.nihil_engine.nihil_utils_plugin.DataKeys
//import cz.nihil_engine.nihil_utils_plugin.debugger.lldb.LLDBDebuggerHandler


class MainActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
//        val simpleConnect = project.messageBus.connect()
//        Disposer.register(project, simpleConnect)
//
//        simpleConnect.subscribe(
//            XDebuggerManager.TOPIC, object : XDebuggerManagerListener {
//
//                override fun processStarted(process: XDebugProcess) {
//                    if (process is CidrDebugProcess) {
//                        ApplicationManager.getApplication().invokeLater {
//                            addUserData(process)
//                        }
//                    }
//                }
//            }
//        )
//
//        XDebuggerManager.getInstance(project).getDebugProcesses(CidrDebugProcess::class.java).forEach { process ->
//            addUserData(process)
//        }
    }

//    fun addUserData(process: CidrDebugProcess) {
//        synchronized(process) {
//            val debugHandler = when (val driver = process.driverInTests) {
//                is LLDBDriver -> {
//                    LLDBDebuggerHandler(driver)
//                }
//
//                is GDBDriver -> {
//                    //GDBDebugHandler(driver)
//                    null
//                }
//
//                else -> {
//                    if (driver == null || driver.javaClass.canonicalName == null) {
//
//                    } else {
//
//                    }
//                    null
//                }
//            }
//
//            process.putUserData(DataKeys.debuggerHandler, debugHandler)
//        }
//    }

}