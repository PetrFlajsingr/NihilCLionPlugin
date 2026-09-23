package cz.nihil_engine.nihil_utils_plugin.scaffold

import java.io.File

/** What the new-module dialog needs to know about the engine's source tree. */
object EngineTree {

    /** Not domain groups: they hold targets of other kinds. */
    private val NON_GROUPS = setOf("apps", "tests", "tools")
    private val LIBRARY_CALL = Regex("""nihil_(?:library|interface_library)\s*\(([^)]*)\)""")
    private val NAME_ARG = Regex("""\bNAME\s+([\w.]+)""")

    /** Domain groups under src/, e.g. foundation, render. */
    fun groups(root: File): List<String> =
        File(root, "src").listFiles { f -> f.isDirectory && f.name !in NON_GROUPS && !f.name.startsWith(".") }
            ?.map { it.name }?.sorted().orEmpty()

    /** Names of every `nihil_library` / `nihil_interface_library`, i.e. the `NihilEngine::<Name>` targets. */
    fun libraryNames(root: File): List<String> =
        File(root, "src").walkTopDown()
            .onEnter { it.name != "third_party" && !it.name.startsWith(".") && !it.name.startsWith("cmake-build") }
            .filter { it.isFile && it.name == "CMakeLists.txt" }
            .flatMap { libraryNamesIn(it.readText()) }
            .distinct().sorted().toList()

    internal fun libraryNamesIn(cmake: String): Sequence<String> =
        LIBRARY_CALL.findAll(cmake).mapNotNull { NAME_ARG.find(it.groupValues[1])?.groupValues?.get(1) }
}
