package cz.nihil_engine.nihil_utils_plugin

import com.intellij.openapi.util.Key
import cz.nihil_engine.nihil_utils_plugin.debugger.DebuggerHandler

object DataKeys {

    val debuggerHandler = Key.create<DebuggerHandler>("DEBUGGER_HANDLER")
}