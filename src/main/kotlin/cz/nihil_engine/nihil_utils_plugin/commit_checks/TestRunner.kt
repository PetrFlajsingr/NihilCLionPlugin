package cz.nihil_engine.nihil_utils_plugin.commit_checks

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.jetbrains.cidr.cpp.cmake.workspace.CMakeWorkspace
import com.jetbrains.cidr.cpp.toolchains.CPPEnvironment
import com.jetbrains.cidr.lang.toolchains.CidrToolEnvironment
import java.io.File

/** The test profile's build tree, and the toolchain environment its build and test executables need. */
class TestTree(val profile: String, val buildDir: File, val cmake: String, private val environment: CPPEnvironment?) {

    /** Test target -> executable, read fresh: the tree changes whenever CMake regenerates it. */
    fun targets(): Map<String, File> = NinjaTestTargets.read(buildDir)

    fun prepare(cmd: GeneralCommandLine, forRun: Boolean): GeneralCommandLine {
        // MSVC's cl.exe and its INCLUDE/LIB only exist in the toolchain's (vcvars) environment
        environment?.prepare(cmd, if (forRun) CidrToolEnvironment.PrepareFor.RUN else CidrToolEnvironment.PrepareFor.BUILD)
        return cmd
    }

    companion object {
        /** Null with a reason when [profile] isn't a loaded CMake profile. */
        fun resolve(project: Project, profile: String): Pair<TestTree?, String?> {
            val info = CMakeWorkspace.getInstance(project).getCMakeProfileInfoByName(profile)
                ?: return null to "CMake profile \"$profile\" isn't loaded. Enable it in Settings | Build, Execution, Deployment | CMake and reload the project."
            val buildDir = info.generationDir
            val cmake = info.environment?.cMake?.executablePath
                ?: return null to "CMake profile \"$profile\" has no CMake executable in its toolchain."
            if (!File(buildDir, "build.ninja").isFile) {
                return null to "The \"$profile\" build tree (${buildDir.path}) isn't generated with Ninja yet. Reload the CMake project."
            }
            return TestTree(profile, buildDir, cmake, info.environment) to null
        }
    }
}

enum class TestStatus { PASSED, FAILED, CRASHED }

data class TargetRun(val target: String, val status: TestStatus, val summary: Catch2Summary?, val exitCode: Int, val seconds: Double, val output: String)

/** Builds test targets and runs their Catch2 executables, reporting output to [indicator]. */
class TestRunner(private val tree: TestTree, private val indicator: ProgressIndicator) {

    /** Exit code and the combined output of `cmake --build` for [targets]. */
    fun build(targets: List<String>): Pair<Int, String> {
        indicator.text = "Building ${targets.joinToString()} (${tree.profile})"
        val cmd = GeneralCommandLine(tree.cmake, "--build", tree.buildDir.path, "--target", *targets.toTypedArray())
            .withWorkingDirectory(tree.buildDir.toPath())
        return run(tree.prepare(cmd, forRun = false))
    }

    fun test(target: String, exe: File): TargetRun {
        indicator.text = "Running $target"
        if (!exe.isFile) return TargetRun(target, TestStatus.CRASHED, null, -1, 0.0, "Executable not found: ${exe.path}")
        // Compact reporter: quiet on success, per-assertion detail on failure
        val cmd = GeneralCommandLine(exe.path, "-r", "compact").withWorkingDirectory(exe.parentFile.toPath())
        val start = System.nanoTime()
        val (exitCode, output) = run(tree.prepare(cmd, forRun = true))
        val seconds = (System.nanoTime() - start) / 1e9
        val summary = Catch2Summary.parse(output)
        val status = when {
            exitCode == 0 -> TestStatus.PASSED
            summary == null -> TestStatus.CRASHED
            else -> TestStatus.FAILED
        }
        return TargetRun(target, status, summary, exitCode, seconds, output)
    }

    private fun run(cmd: GeneralCommandLine): Pair<Int, String> {
        val output = StringBuffer()
        val handler = CapturingProcessHandler(cmd)
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                if (outputType == ProcessOutputTypes.SYSTEM) return
                output.append(event.text)
                event.text.trim().takeIf { it.isNotEmpty() }?.let { indicator.text2 = it.lineSequence().last() }
            }
        })
        val result = handler.runProcessWithProgressIndicator(indicator)
        if (result.isCancelled) throw ProcessCanceledException()
        return result.exitCode to output.toString()
    }
}
