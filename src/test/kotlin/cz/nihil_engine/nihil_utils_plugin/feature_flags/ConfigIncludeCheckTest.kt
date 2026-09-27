package cz.nihil_engine.nihil_utils_plugin.feature_flags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigIncludeCheckTest {

    private val src = "F:/Engine/src"

    private val check: ConfigIncludeCheck = run {
        val jobs = "$src/system/Jobs/config/config_debug.hpp"
        val core = "$src/foundation/Core/config/config_debug.hpp"
        val macros = listOf(Located(jobs, "NIHIL_JOBS_ENABLE_FIBERS"), Located(core, "NIHIL_CONTAINERS_ENABLE_BOUND_CHECKS"))
        val features = CppFlagScanner.features("inline constexpr Feature ParanoidAsserts{\"Enable paranoid assert checks\", true};")
            .map { Located(core, it) }
        val (owners, warnings) = ConfigIncludeCheck.owners(
            mapOf(
                "$src/system/Jobs" to listOf("NihilJobs"),
                "$src/foundation/Core" to listOf("NihilCore"),
                "$src/render/Broken" to listOf("NihilBroken", "NihilBroken2"),
            ),
            macros,
            features,
        )
        assertEquals(setOf("NihilJobs/config.hpp", "NihilCore/config.hpp"), owners.keys)
        assertEquals(1, warnings.size)
        ConfigIncludeCheck(owners)
    }

    @Test
    fun `reports symbols whose config header isn't included`() {
        val text = """
            #include <NihilCore/config.hpp>
            #if NIHIL_IS_ENABLED(NIHIL_JOBS_ENABLE_FIBERS)
            static_assert(config::ParanoidAsserts);
            #endif
        """.trimIndent()
        val missing = check.check("$src/system/Other/Private/Sources/Thing.cpp", text).single()
        assertEquals("NihilJobs/config.hpp", missing.include)
        assertEquals(mapOf("NIHIL_JOBS_ENABLE_FIBERS" to text.indexOf("NIHIL_JOBS_ENABLE_FIBERS")), missing.symbols)
    }

    @Test
    fun `feature constants need config prefix and comments or strings don't count`() {
        val text = """
            // NIHIL_JOBS_ENABLE_FIBERS in a comment
            const char *s = "NIHIL_JOBS_ENABLE_FIBERS";
            bool paranoid = ParanoidAsserts;
            bool reallyParanoid = nihil::config::ParanoidAsserts;
        """.trimIndent()
        val missing = check.check("$src/a/B.ixx", text).single()
        assertEquals("NihilCore/config.hpp", missing.include)
        assertEquals(setOf("config::ParanoidAsserts"), missing.symbols.keys)
    }

    @Test
    fun `files in config directories and other suffixes aren't checked`() {
        val text = "#define X NIHIL_JOBS_ENABLE_FIBERS\n"
        assertTrue(check.check("$src/system/Jobs/config/config_debug.hpp", text).isEmpty())
        assertTrue(check.check("$src/system/Jobs/Private/thing.inl", text).isEmpty())
        assertEquals(1, check.check("$src/system/Jobs/Private/thing.h", text).size)
    }

    @Test
    fun `quoted includes count`() {
        val text = "#include \"NihilJobs/config.hpp\"\nbool b = NIHIL_IS_ENABLED(NIHIL_JOBS_ENABLE_FIBERS);\n"
        assertTrue(check.check("$src/x/Y.cpp", text).isEmpty())
    }
}
