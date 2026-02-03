package cz.nihil_engine.nihil_utils_plugin.debugger

// TODO: GDB
// TODO: Linux vs Windows
interface DebuggerHandler {
    fun removeBreakpointInstruction(): Boolean
}