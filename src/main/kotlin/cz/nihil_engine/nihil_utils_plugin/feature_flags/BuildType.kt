package cz.nihil_engine.nihil_utils_plugin.feature_flags

/** NIHIL_BUILD_TYPE values from NihilMacros/nihil.hpp, in their numeric order. */
enum class BuildType(val short: String, val displayName: String, val macro: String) {
    DEBUG("D", "Debug", "NIHIL_BUILD_TYPE_DEBUG"),
    DEVELOPMENT("Dv", "Development", "NIHIL_BUILD_TYPE_DEVELOPMENT"),
    PROFILING("P", "Profiling", "NIHIL_BUILD_TYPE_PROFILING"),
    RELEASE("R", "Release", "NIHIL_BUILD_TYPE_RELEASE"),
    TEST("T", "Test", "NIHIL_BUILD_TYPE_TEST");

    /** The per-library header the dispatcher `config.hpp` includes for this build type. */
    val configFileName: String get() = "config_${name.lowercase()}.hpp"

    companion object {
        fun fromMacro(macro: String): BuildType? = entries.firstOrNull { it.macro == macro }

        fun fromConfigFileName(fileName: String): BuildType? = entries.firstOrNull { it.configFileName == fileName }

        /**
         * cmake/NihilFlags.cmake's mapping, used when the script's own `NIHIL_BUILD_TYPE` definitions can't be
         * read: `NIHIL_ENABLE_TESTS=ON` wins, then CMAKE_BUILD_TYPE.
         */
        fun fallback(cmakeBuildType: String?, testsEnabled: Boolean?): BuildType? = when {
            testsEnabled == null -> null
            testsEnabled -> TEST
            else -> when (cmakeBuildType) {
                "Debug" -> DEBUG
                "Development" -> DEVELOPMENT
                "Profiling" -> PROFILING
                "RelWithDebInfo", "Release" -> RELEASE
                else -> null
            }
        }
    }
}
