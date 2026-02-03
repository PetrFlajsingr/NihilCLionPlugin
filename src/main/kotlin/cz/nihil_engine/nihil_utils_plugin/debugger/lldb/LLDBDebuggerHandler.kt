package cz.nihil_engine.nihil_utils_plugin.debugger.lldb
//
//import com.jetbrains.cidr.execution.debugger.backend.lldb.LLDBDriver
//import com.jetbrains.cidr.execution.debugger.backend.lldb.ProtobufMessageFactory
//import com.jetbrains.cidr.execution.debugger.backend.lldb.auto_generated.ProtocolResponses
//import cz.nihil_engine.nihil_utils_plugin.debugger.DebuggerHandler
//import java.util.concurrent.CompletableFuture
//import java.util.concurrent.ExecutionException
//import java.util.concurrent.TimeUnit
//import java.util.concurrent.TimeoutException
//
//class LLDBDebuggerHandler(private val driver: LLDBDriver) : DebuggerHandler {
//    override fun removeBreakpointInstruction(): Boolean {
//        val readPCCommand = "memory read -s1 -fx -c 1 \$PC-1"
//        val request = ProtobufMessageFactory
//            .handleConsoleCommand(-1, -1, readPCCommand)
//
//        val reply = try {
//            CompletableFuture.supplyAsync {
//                driver.sendMessageAndWaitForReply(
//                    request,
//                    ProtocolResponses.HandleConsoleCommand_Res::class.java,
//                )
//            }.get(3, TimeUnit.SECONDS)
//        } catch (e: TimeoutException) {
//            return false
//        } catch (e: ExecutionException) {
//            return false
//        }
//        if (reply.hasErr()) {
//            return false
//        }
//        val currentInstruction = reply.out.split(' ')[1].trim().substring(2)
//        // 0xcc is int3 on windows
//        if (currentInstruction.toInt(16) != 0xcc) {
//            return false
//        }
//
//        val writePCCommand = "memory write \$PC-1 0x90"
//        val requestWrite = ProtobufMessageFactory
//            .handleConsoleCommand(-1, -1, writePCCommand)
//        val replyWrite = try {
//            CompletableFuture.supplyAsync {
//                driver.sendMessageAndWaitForReply(
//                    requestWrite,
//                    ProtocolResponses.HandleConsoleCommand_Res::class.java,
//                )
//            }.get(3, TimeUnit.SECONDS)
//        } catch (e: TimeoutException) {
//            return false
//        } catch (e: ExecutionException) {
//            return false
//        }
//        return !replyWrite.hasErr()
//    }
//}