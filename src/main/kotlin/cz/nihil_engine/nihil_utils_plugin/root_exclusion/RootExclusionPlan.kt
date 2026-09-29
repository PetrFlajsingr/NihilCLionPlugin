package cz.nihil_engine.nihil_utils_plugin.root_exclusion

import com.intellij.openapi.util.io.FileUtil

/**
 * Which directories under the project root the plugin keeps excluded, and how to get there from what is excluded now.
 * Paths are system-independent and compared the way the file system does (case-insensitively on Windows).
 *
 * A game repo keeps the engine as a submodule at `engine/`. A profile whose NIHIL_ENGINE_DIR points elsewhere (a linked
 * engine checkout) builds against that checkout, and indexing `engine/` as well would declare every engine module twice.
 */
object RootExclusionPlan {
    const val ENGINE_SUBDIR = "engine"

    data class Wanted(
        /** Directories that should be excluded. */
        val excluded: List<String>,
        /** Directories the plugin has no opinion on right now: an exclusion it made there stays. */
        val keep: List<String> = emptyList(),
    )

    /**
     * @param engineDir NIHIL_ENGINE_DIR of the selected profile, or null when no profile sets it (or its cache isn't generated yet)
     * @param engineRootExists whether `<base>/engine` is a directory
     * @param disabledGenerationDirs existing generation dirs of disabled CMake profiles
     * @param enabledGenerationDirs generation dirs of enabled CMake profiles, which CLion excludes itself
     */
    fun wanted(
        base: String,
        engineDir: String?,
        engineRootExists: Boolean,
        disabledGenerationDirs: List<String>,
        enabledGenerationDirs: List<String>,
    ): Wanted {
        val excluded = mutableListOf<String>()
        val keep = mutableListOf<String>()

        val engineRoot = "$base/$ENGINE_SUBDIR"
        when {
            // Unknown: switching back and forth while a cache is missing would reindex engine/ each time
            engineDir == null -> keep += engineRoot
            // The project is (inside) the engine itself
            FileUtil.isAncestor(engineDir, base, false) -> {}
            engineRootExists && !FileUtil.isAncestor(engineRoot, engineDir, false) -> excluded += engineRoot
        }

        for (dir in disabledGenerationDirs) {
            if (!FileUtil.isAncestor(base, dir, true)) continue
            if (FileUtil.isAncestor(dir, engineRoot, false)) continue // would hide engine/ from a pinned profile
            if (enabledGenerationDirs.any { FileUtil.isAncestor(dir, it, false) || FileUtil.isAncestor(it, dir, false) }) continue
            if (excluded.none { FileUtil.pathsEqual(it, dir) }) excluded += dir
        }
        return Wanted(excluded, keep)
    }

    data class Changes(val add: List<String>, val remove: List<String>, val owned: List<String>)

    /**
     * Only exclusions the plugin made itself ([owned]) are ever removed; a directory someone excluded by hand is left
     * alone, and isn't claimed when it happens to be wanted too. An owned directory that is no longer excluded was
     * un-excluded by hand and is forgotten.
     */
    fun changes(wanted: Wanted, excludedNow: List<String>, owned: List<String>): Changes {
        fun List<String>.has(path: String) = any { FileUtil.pathsEqual(it, path) }

        val stillOwned = owned.filter { excludedNow.has(it) }
        val add = wanted.excluded.filter { !excludedNow.has(it) }
        val remove = stillOwned.filter { !wanted.excluded.has(it) && !wanted.keep.has(it) }
        return Changes(add, remove, stillOwned.filter { !remove.has(it) } + add)
    }
}
