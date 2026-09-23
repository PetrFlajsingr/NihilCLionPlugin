package cz.nihil_engine.nihil_utils_plugin.debugger

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.jetbrains.cidr.execution.debugger.CidrDebugProcess
import com.jetbrains.cidr.execution.debugger.backend.DebuggerDriver
import com.jetbrains.cidr.execution.debugger.backend.LLFrame
import com.jetbrains.cidr.execution.debugger.backend.LLThread
import com.jetbrains.cidr.execution.debugger.memory.Address
import com.jetbrains.cidr.execution.debugger.memory.AddressRange
import cz.nihil_engine.nihil_utils_plugin.asserts.AssertMacroService
import cz.nihil_engine.nihil_utils_plugin.asserts.AssertSite
import java.util.concurrent.CompletableFuture

/** Bytes to write so a break site stops breaking. */
data class BreakPatch(val address: Address, val bytes: ByteArray, val description: String)

/** The debugger is stopped on the `int3` of a NihilEngine assert. */
data class AssertBreakHit(
    val site: AssertSite,
    val file: String,
    /** How to disable this break site in the running process; null when it can't be done safely. */
    val patch: BreakPatch?,
)

/**
 * Recognises a stop on an assert's debugger break (`nihil::DebugBreak`, called from NIHIL_ASSERTION_IMPL /
 * NIHIL_ERROR_IMPL when AssertManager::shouldStopDebugger says so):
 *
 * 1. The byte before the program counter (or at it, depending on the debugger) is `int3` (0xCC). A normal
 *    breakpoint on an assert line doesn't match: debuggers hide their own breakpoint bytes from memory reads.
 * 2. The first frame outside the assert machinery (debugger.ixx, asserts*.hpp, *DebugBreak*) has a source file,
 *    and the source there holds an assert macro invocation, which gives the ID and the assert type.
 *
 * The ID comes from source, not from the console: logging is asynchronous, so the failure report is usually
 * still queued when the debugger stops the process.
 */
object AssertBreakAnalyzer {

    private val log = Logger.getInstance(AssertBreakAnalyzer::class.java)
    private const val INT3: Byte = 0xCC.toByte()
    private const val MAX_FRAMES = 8
    private val MACHINERY_FILES = listOf("debugger.ixx", "asserts_impl.hpp", "asserts.hpp")

    private data class RawStop(val frames: List<LLFrame>, val siteFrame: LLFrame, val int3: Address, val patch: BreakPatch?)

    fun analyze(process: CidrDebugProcess, thread: LLThread): CompletableFuture<AssertBreakHit?> =
        process.postCommand(CidrDebugProcess.DebuggerCommand { driver -> readStop(driver, thread) })
            .thenApply { raw -> raw?.let { toHit(process.session.project, it) } }

    private fun readStop(driver: DebuggerDriver, thread: LLThread): RawStop? {
        val frames = driver.getFrames(thread, 0, MAX_FRAMES).list
        val top = frames.firstOrNull() ?: return null
        val pc = top.programCounter
        val bytes = read(driver, pc.minus(1), 2) ?: return null
        val int3 = when {
            bytes[0] == INT3 -> pc.minus(1)
            bytes[1] == INT3 -> pc
            else -> return null
        }
        val siteIndex = frames.indexOfFirst { isSiteFrame(it) }
        if (siteIndex < 0) {
            // Typically a build without debug info (e.g. CMake's Release): no frame has a source file to find the assert in.
            log.info("Stopped on int3 at $int3 but no frame has a source file outside the assert machinery; frames: " +
                frames.joinToString { "${it.function} (${it.file}:${it.line})" })
            return null
        }
        val patch = if (driver.supportsMemoryWrite()) planPatch(driver, frames, siteIndex, int3) else null
        return RawStop(frames, frames[siteIndex], int3, patch)
    }

    /** The first frame outside the assert machinery: where the assert macro was invoked. */
    private fun isSiteFrame(frame: LLFrame): Boolean {
        val file = frame.file ?: return false
        val name = file.substringAfterLast('/').substringAfterLast('\\')
        return name !in MACHINERY_FILES && !isDebugBreak(frame)
    }

    /**
     * When everything between the `int3` and the assert site was inlined into the site, the `int3` belongs to this
     * site alone: replace it with `nop`. Otherwise the `int3` sits in a real function shared by every assert
     * (nihil::DebugBreak in Debug builds, which don't inline), so disable the site's own `call` into it instead: a
     * 5-byte `call rel32` or a 6-byte `call [rip+disp32]` just before the site frame's return address, replaced by a
     * multi-byte `nop` of the same length.
     */
    private fun planPatch(driver: DebuggerDriver, frames: List<LLFrame>, siteIndex: Int, int3: Address): BreakPatch? {
        if ((0 until siteIndex).all { isInlined(frames, it) }) return BreakPatch(int3, byteArrayOf(0x90.toByte()), "int3 -> nop")

        val ret = frames[siteIndex].programCounter
        val before = read(driver, ret.minus(6), 6) ?: return null
        return when {
            before[1] == 0xE8.toByte() ->
                BreakPatch(ret.minus(5), byteArrayOf(0x0F, 0x1F, 0x44, 0x00, 0x00), "call DebugBreak -> nop")
            before[0] == 0xFF.toByte() && before[1] == 0x15.toByte() ->
                BreakPatch(ret.minus(6), byteArrayOf(0x66, 0x0F, 0x1F, 0x44, 0x00, 0x00), "call [DebugBreak] -> nop")
            else -> null
        }
    }

    /**
     * An inlined frame has no code of its own: it shares its caller's program counter. Checked in addition to
     * [LLFrame.getInlined], which the GDB backend leaves false even for inline frames.
     */
    private fun isInlined(frames: List<LLFrame>, index: Int): Boolean =
        frames[index].inlined || frames.getOrNull(index + 1)?.programCounter == frames[index].programCounter

    /** nihil::DebugBreak, or a compiler's own __debugbreak when it isn't an intrinsic (MinGW). */
    private fun isDebugBreak(frame: LLFrame) = frame.function.orEmpty().contains("debugbreak", ignoreCase = true)

    private fun read(driver: DebuggerDriver, start: Address, length: Int): ByteArray? {
        val hunks = driver.dumpMemory(AddressRange(start, start.plus(length - 1)))
        val bytes = hunks.flatMap { it.bytes }
        return if (bytes.size >= length) bytes.take(length).toByteArray() else null
    }

    private fun toHit(project: Project, raw: RawStop): AssertBreakHit? {
        val siteFrame = raw.siteFrame
        val file = siteFrame.file ?: return null
        val site = ReadAction.computeBlocking<AssertSite?, RuntimeException> {
            val vf = LocalFileSystem.getInstance().findFileByPath(FileUtil.toSystemIndependentName(file))
                ?: return@computeBlocking null
            val text = FileDocumentManager.getInstance().getDocument(vf)?.charsSequence ?: return@computeBlocking null
            AssertSite.findAt(text, siteFrame.line, AssertMacroService.getInstance(project).macros) // LLFrame lines are 0-based
        }
        if (site == null) {
            log.info("Stopped on int3 at ${raw.int3} but found no assert macro at $file:${siteFrame.line + 1}; frames: " +
                raw.frames.joinToString { "${it.function} (${it.file}:${it.line})" })
            return null
        }
        log.info("Assert break ${site.kind.typeName} ${site.idText} at $file:${siteFrame.line + 1}, patch: ${raw.patch?.let { "${it.description} at ${it.address}" }}; frames: " +
            raw.frames.joinToString { "${it.function} (${it.file?.substringAfterLast('/')}:${it.line}, inlined=${it.inlined}, pc=${it.programCounter})" })
        return AssertBreakHit(site, file, raw.patch)
    }

    fun applyPatch(process: CidrDebugProcess, patch: BreakPatch): CompletableFuture<Boolean> =
        process.postCommand(CidrDebugProcess.DebuggerCommand { driver ->
            driver.writeMemory(patch.address, patch.bytes)
            true
        }).exceptionally { false }
}
