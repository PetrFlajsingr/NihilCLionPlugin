package cz.nihil_engine.nihil_utils_plugin.commit_checks

import java.io.File

/**
 * The test executables a Ninja build tree defines. CMake's Ninja generator emits a phony alias per target
 * (`build NihilTestCommon: phony F$:\...\NihilTestCommon_Release.exe`) next to an alias named after the output
 * file; the target aliases map each test target to the executable it produces.
 */
object NinjaTestTargets {

    private val PHONY = Regex("""^build (${TestSelection.TARGET_PREFIX}[^\s:]*): phony (.+)$""")

    /** Test target -> executable. Empty when the tree has no build.ninja (not generated, or not Ninja). */
    fun read(buildDir: File): Map<String, File> {
        val manifest = File(buildDir, "build.ninja")
        if (!manifest.isFile) return emptyMap()
        return manifest.useLines { parse(it) }
    }

    fun parse(lines: Sequence<String>): Map<String, File> {
        val targets = sortedMapOf<String, File>()
        for (line in lines) {
            val m = PHONY.matchEntire(line) ?: continue
            val name = m.groupValues[1]
            // The alias named after the output file, and the windowed test apps, which aren't Catch2 binaries
            if (name.endsWith(".exe", ignoreCase = true) || name.startsWith("${TestSelection.TARGET_PREFIX}Apps")) continue
            val output = unescape(m.groupValues[2].trim())
            if (' ' in m.groupValues[2].replace("$ ", "")) continue // several inputs: not a single executable
            targets[name] = File(output)
        }
        return targets
    }

    private fun unescape(path: String): String =
        path.replace("$:", ":").replace("$ ", " ").replace("$$", "$")
}
