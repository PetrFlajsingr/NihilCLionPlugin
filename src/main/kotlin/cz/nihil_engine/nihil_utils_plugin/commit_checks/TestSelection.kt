package cz.nihil_engine.nihil_utils_plugin.commit_checks

import cz.nihil_engine.nihil_utils_plugin.scaffold.EngineTree
import java.io.File

/** A library whose tests a commit has to pass, and the files and directories whose content those tests cover. */
data class LibraryTests(
    /** `NihilEngine::<name>` without the namespace, e.g. Common. */
    val library: String,
    val targets: List<String>,
    val inputs: List<File>,
) {
    /** How the commit message names the library, e.g. NihilCommon. */
    val displayName: String get() = "Nihil$library"
}

/**
 * Which test targets a commit needs, from the engine tree's layout:
 * - a library's sources live under the nearest directory whose CMakeLists.txt declares it with `nihil_library`;
 * - its tests live in `src/tests/Nihil<Name>` and build the `NihilTest<Name>` target, or `NihilTest<Name>_<variant>`
 *   when one test directory builds several executables (RDG_vulkan, RDG_d3d12);
 * - anything else under `src/tests` (LoggerStartup.cpp, TestUtils) is shared by every test executable.
 */
object TestSelection {

    const val TARGET_PREFIX = "NihilTest"
    private const val TESTS_DIR = "src/tests"

    /**
     * [changedFiles] are absolute paths of files the commit adds, modifies or deletes.
     * [availableTargets] are the test targets the test profile's build tree defines.
     * [overrides] replace the naming convention for a library; an empty list means the library has no tests.
     */
    fun select(
        root: File,
        changedFiles: Collection<File>,
        availableTargets: Collection<String>,
        overrides: Map<String, List<String>>,
    ): List<LibraryTests> {
        val touched = librariesTouched(root, changedFiles)
        val libraries = if (touched.allTests) allTestedLibraries(availableTargets, overrides) else touched.libraries
        return libraries.sorted().mapNotNull { library ->
            val targets = targetsFor(library, availableTargets, overrides)
            if (targets.isEmpty()) return@mapNotNull null
            LibraryTests(library, targets, inputs(root, library, touched.libraryDirs[library]))
        }
    }

    data class Touched(
        val libraries: Set<String>,
        /** The directory each library was found in, from walking up from a changed file. */
        val libraryDirs: Map<String, File>,
        /** A shared test file changed, so every test executable is affected. */
        val allTests: Boolean,
    )

    fun librariesTouched(root: File, changedFiles: Collection<File>): Touched {
        val src = File(root, "src").canonicalFile
        val tests = File(root, TESTS_DIR).canonicalFile
        val libraries = sortedSetOf<String>()
        val dirs = HashMap<String, File>()
        var allTests = false
        val declaredIn = HashMap<File, List<String>>()

        for (file in changedFiles.map { it.canonicalFile }) {
            if (file.startsWith(tests)) {
                val first = file.relativeTo(tests).invariantSeparatorsPath.substringBefore('/')
                // A file directly in src/tests has no directory part: it's shared, like TestUtils
                val isLibraryTestDir = first != file.name && first.startsWith("Nihil") && first != "Nihil"
                if (isLibraryTestDir) libraries += first.removePrefix("Nihil") else allTests = true
                continue
            }
            if (!file.startsWith(src)) continue
            var dir = file.parentFile
            while (dir != null && dir.startsWith(src)) {
                val names = declaredIn.getOrPut(dir) { librariesDeclaredIn(dir) }
                if (names.isNotEmpty()) {
                    libraries += names
                    names.forEach { dirs.putIfAbsent(it, dir) }
                    break
                }
                dir = dir.parentFile
            }
        }
        return Touched(libraries, dirs, allTests)
    }

    fun targetsFor(library: String, availableTargets: Collection<String>, overrides: Map<String, List<String>>): List<String> {
        overrides[library]?.let { return it }
        val exact = "$TARGET_PREFIX$library"
        return availableTargets.filter { it == exact || it.startsWith(exact + "_") }.sorted()
    }

    /** Library of a test target by convention, e.g. NihilTestRDG_vulkan -> RDG. */
    fun libraryOf(target: String, overrides: Map<String, List<String>>): String? {
        overrides.entries.firstOrNull { target in it.value }?.let { return it.key }
        if (!target.startsWith(TARGET_PREFIX)) return null
        return target.removePrefix(TARGET_PREFIX).substringBefore('_').takeIf { it.isNotEmpty() }
    }

    private fun allTestedLibraries(availableTargets: Collection<String>, overrides: Map<String, List<String>>): Set<String> =
        (availableTargets.mapNotNull { libraryOf(it, overrides) } + overrides.filterValues { it.isNotEmpty() }.keys).toSet()

    /**
     * What a cached pass depends on: the library's own directory, its test directory and the shared test files.
     * [knownDir] comes from the changed files; without it (only tests changed) the tree is searched for the library.
     */
    private fun inputs(root: File, library: String, knownDir: File?): List<File> {
        val tests = File(root, TESTS_DIR)
        val libraryDir = knownDir ?: findLibraryDir(root, library)
        val shared = tests.listFiles { f -> f.isFile || f.name == "TestUtils" }?.sortedBy { it.name }.orEmpty()
        return (listOfNotNull(libraryDir, File(tests, "Nihil$library").takeIf { it.isDirectory }) + shared)
            .map { it.canonicalFile }.distinct()
    }

    private fun findLibraryDir(root: File, library: String): File? =
        File(root, "src").walkTopDown()
            .onEnter { it.name != "tests" && it.name != "third_party" && !it.name.startsWith(".") && !it.name.startsWith("cmake-build") }
            .filter { it.isFile && it.name == "CMakeLists.txt" }
            .firstOrNull { library in EngineTree.libraryNamesIn(it.readText()) }
            ?.parentFile

    private fun librariesDeclaredIn(dir: File): List<String> {
        val cmake = File(dir, "CMakeLists.txt")
        if (!cmake.isFile) return emptyList()
        return try {
            EngineTree.libraryNamesIn(cmake.readText()).toList()
        } catch (_: Exception) {
            emptyList()
        }
    }
}
