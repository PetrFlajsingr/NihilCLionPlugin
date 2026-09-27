package cz.nihil_engine.nihil_utils_plugin.feature_flags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CppFlagScannerTest {

    @Test
    fun `column aligned and indented defines`() {
        val text = """
            #pragma once
            #include <NihilConfig/Feature.hpp>
            namespace nihil::config {
            // clang-format off
            #define NIHIL_JOBS_ENABLE_FIBERS               NIHIL_ENABLED
            #define NIHIL_JOBS_ENABLE_PROFILING            NIHIL_DISABLED
            #    define NIHIL_JOBS_ENABLE_NAMES NIHIL_ENABLED
            // clang-format on
            }
        """.trimIndent()
        val defines = CppFlagScanner.defines(text)
        assertEquals(listOf("NIHIL_JOBS_ENABLE_FIBERS", "NIHIL_JOBS_ENABLE_PROFILING", "NIHIL_JOBS_ENABLE_NAMES"), defines.map { it.name })
        assertEquals(listOf(DefineValue.ENABLED, DefineValue.DISABLED, DefineValue.ENABLED), defines.map { it.value })
        assertEquals(listOf(4, 5, 6), defines.map { it.line })
        assertTrue(defines.none { it.guarded || it.conditions.isNotEmpty() })
    }

    @Test
    fun `ifndef guard marks an overridable default`() {
        val text = """
            #define NIHIL_LUA_SAFE_STACK NIHIL_DISABLED
            #ifndef NIHIL_LUA_DEFINITIONS
            #    define NIHIL_LUA_DEFINITIONS NIHIL_DISABLED
            #endif
            #if !defined(NIHIL_OTHER)
            #define NIHIL_OTHER NIHIL_ENABLED
            #endif
        """.trimIndent()
        val byName = CppFlagScanner.defines(text).associateBy { it.name }
        assertFalse(byName.getValue("NIHIL_LUA_SAFE_STACK").guarded)
        assertTrue(byName.getValue("NIHIL_LUA_DEFINITIONS").guarded)
        assertEquals(emptyList<PpCondition>(), byName.getValue("NIHIL_LUA_DEFINITIONS").conditions)
        assertTrue(byName.getValue("NIHIL_OTHER").guarded)
    }

    @Test
    fun `defines under if and else keep their conditions`() {
        val text = """
            #include <NihilMacros/nihil.hpp>

            #if defined(__cpp_impl_reflection) && defined(__cpp_expansion_statements)
            #    define NIHIL_REFLECTION NIHIL_ENABLED
            #else
            #    define NIHIL_REFLECTION NIHIL_DISABLED
            #endif
        """.trimIndent()
        val defines = CppFlagScanner.defines(text)
        assertEquals(2, defines.size)
        assertEquals("if", defines[0].conditions.single().directive)
        assertEquals("else", defines[1].conditions.single().directive)
        assertTrue(defines.all { d -> d.conditions.all(CppFlagScanner::isCompilerCondition) })
    }

    @Test
    fun `comments, function-like macros and multi-token values are skipped`() {
        val text = """
            // #define NIHIL_COMMENTED NIHIL_ENABLED
            /*
            #define NIHIL_BLOCK_COMMENTED NIHIL_ENABLED
            */
            #define NIHIL_IS_ENABLED(x) ((1 x 1) == 2)
            #define NIHIL_ENABLED +
            #define NIHIL_SUM 1 + 2
            #define NIHIL_ALIAS NIHIL_OTHER_FLAG
        """.trimIndent()
        val defines = CppFlagScanner.defines(text)
        assertEquals(listOf("NIHIL_ENABLED", "NIHIL_ALIAS"), defines.map { it.name })
        assertEquals(DefineValue.OTHER, defines[1].value)
        assertEquals("NIHIL_OTHER_FLAG", defines[1].rawValue)
    }

    @Test
    fun `all define names match the raw text like the engine script`() {
        val text = "#define NIHIL_A NIHIL_ENABLED\n#  define NIHIL_B(x) x\n// #define NIHIL_C 1\n"
        assertEquals(listOf("NIHIL_A", "NIHIL_B"), CppFlagScanner.allDefineNames(text))
    }

    @Test
    fun `feature constants, also multi-line and without inline`() {
        val text = """
            namespace nihil::config {
            inline constexpr Feature LogFlushError{"Flush every error message and higher", true};
            inline constexpr Feature LogLowestAllowed{"Lowest allowed log level, the rest is compiled out", log::Level::Trace};
            constexpr Feature StaticCastCheck{"Enable RTTI checks for nihil::rtti::StaticCast", true};
            inline constexpr Feature MaxTracyTagDepth{
                "Max tag hierarchy levels reported to Tracy as named pools",
                3u};
            // inline constexpr Feature Commented{"x", true};
            }
        """.trimIndent()
        val features = CppFlagScanner.features(text)
        assertEquals(listOf("LogFlushError", "LogLowestAllowed", "StaticCastCheck", "MaxTracyTagDepth"), features.map { it.name })
        assertEquals("log::Level::Trace", features[1].value)
        assertEquals("Enable RTTI checks for nihil::rtti::StaticCast", features[2].description)
        assertEquals("3u", features[3].value)
        assertEquals(4, features[3].line)
    }

    @Test
    fun `uses skip comments, strings and the macro's own definition`() {
        val text = """
            #define NIHIL_IS_ENABLED(x) ((1 x 1) == 2)
            #if NIHIL_IS_ENABLED(NIHIL_PROFILER_ENABLED)
            // NIHIL_IS_ENABLED(NIHIL_IN_COMMENT)
            const char* s = "NIHIL_IS_ENABLED(NIHIL_IN_STRING)";
            if constexpr (NIHIL_IS_ENABLED( NIHIL_JOBS_ENABLE_FIBERS )) {}
            #endif
        """.trimIndent()
        val uses = CppFlagScanner.uses(text)
        assertEquals(listOf("NIHIL_PROFILER_ENABLED", "NIHIL_JOBS_ENABLE_FIBERS"), uses.map { it.name })
        assertEquals("NIHIL_IS_ENABLED( NIHIL_JOBS_ENABLE_FIBERS )", text.substring(uses[1].start, uses[1].end))
    }
}
