package cz.nihil_engine.nihil_utils_plugin.feature_flags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CMakeFlagScriptTest {

    companion object {
        /** Trimmed-down cmake/NihilFlags.cmake with the shapes that matter. */
        val NIHIL_FLAGS = """
            option(NIHIL_ENABLE_TESTS "Enable tests" OFF)
            option(NIHIL_USE_ASAN "Use address sanitizer" OFF)
            option(NIHIL_ENABLE_PROFILER "Enable profiler" OFF)
            option(NIHIL_USE_MIMALLOC "Use mimalloc" ON)

            if (NIHIL_ENABLE_PROFILER)
                if (NIHIL_ENABLE_TESTS)
                    message(FATAL_ERROR "Profiler should not be enabled for tests")
                endif ()
                add_compile_definitions(TRACY_ENABLE)
                # Nothing is recorded until a server attaches (a comment with parens)
                add_compile_definitions(NIHIL_PROFILER_ENABLED=NIHIL_ENABLED)
            else ()
                add_compile_definitions(NIHIL_PROFILER_ENABLED=NIHIL_DISABLED)
            endif ()

            # Build type
            if (NIHIL_ENABLE_TESTS)
                add_compile_definitions(NIHIL_BUILD_TYPE=NIHIL_BUILD_TYPE_TEST)
            elseif (CMAKE_BUILD_TYPE STREQUAL "Debug")
                add_compile_definitions(NIHIL_BUILD_TYPE=NIHIL_BUILD_TYPE_DEBUG)
            elseif (CMAKE_BUILD_TYPE STREQUAL "Development")
                add_compile_definitions(NIHIL_BUILD_TYPE=NIHIL_BUILD_TYPE_DEVELOPMENT)
            elseif (CMAKE_BUILD_TYPE STREQUAL "Profiling")
                add_compile_definitions(NIHIL_BUILD_TYPE=NIHIL_BUILD_TYPE_PROFILING)
                if (CMAKE_CXX_COMPILER_ID STREQUAL "MSVC")
                    list(APPEND NIHIL_COMPILER_FLAGS "/Zo")
                endif ()
            elseif (CMAKE_BUILD_TYPE STREQUAL "RelWithDebInfo")
                add_compile_definitions(NIHIL_BUILD_TYPE=NIHIL_BUILD_TYPE_RELEASE)
            elseif (CMAKE_BUILD_TYPE STREQUAL "Release")
                add_compile_definitions(NIHIL_BUILD_TYPE=NIHIL_BUILD_TYPE_RELEASE)
            else ()
                message(FATAL_ERROR "Unsupported build type ${'$'}{CMAKE_BUILD_TYPE}")
            endif ()

            if (NIHIL_USE_MIMALLOC)
                add_compile_definitions(NIHIL_MIMALLOC_ENABLED=NIHIL_ENABLED)
            else ()
                add_compile_definitions(NIHIL_MIMALLOC_ENABLED=NIHIL_DISABLED)
            endif ()

            if (NIHIL_USE_ASAN)
                add_compile_definitions(NIHIL_ASAN_ENABLED=NIHIL_ENABLED)
            else ()
                add_compile_definitions(NIHIL_ASAN_ENABLED=NIHIL_DISABLED)
            endif ()
        """.trimIndent()
    }

    @Test
    fun `options, definitions and their branch guards`() {
        val script = CMakeFlagScript.parse(NIHIL_FLAGS)
        assertEquals(listOf("NIHIL_ENABLE_TESTS", "NIHIL_USE_ASAN", "NIHIL_ENABLE_PROFILER", "NIHIL_USE_MIMALLOC"), script.options.keys.toList())
        assertEquals("ON", script.options.getValue("NIHIL_USE_MIMALLOC").default)
        assertEquals(setOf("NIHIL_PROFILER_ENABLED", "NIHIL_MIMALLOC_ENABLED", "NIHIL_ASAN_ENABLED"), script.flagNames)

        val profiler = script.definitionsOf("NIHIL_PROFILER_ENABLED")
        assertEquals(listOf("NIHIL_ENABLED", "NIHIL_DISABLED"), profiler.map { it.value })
        assertEquals(listOf(CMakeGuard("NIHIL_ENABLE_PROFILER", true, 5)), profiler[0].guards)
        assertEquals(listOf(CMakeGuard("NIHIL_ENABLE_PROFILER", false, 5)), profiler[1].guards)
        assertEquals(11, profiler[0].line)

        val release = script.definitionsOf("NIHIL_BUILD_TYPE").last()
        assertEquals("NIHIL_BUILD_TYPE_RELEASE", release.value)
        assertEquals(6, release.guards.size)
        assertEquals(CMakeGuard("CMAKE_BUILD_TYPE STREQUAL \"Release\"", true, 30), release.guards.last())
        assertTrue(release.guards.dropLast(1).none { it.mustBe })
    }

    @Test
    fun `evaluator uses three-valued logic`() {
        val vars = mapOf("A" to "ON", "B" to "OFF", "CMAKE_BUILD_TYPE" to "Debug")
        val evaluator = CMakeConditionEvaluator { name -> vars[name]?.let { VariableValue(name, it, VariableOrigin.GENERATION_OPTIONS) } }
        assertEquals(true, evaluator.evaluate("A"))
        assertEquals(false, evaluator.evaluate("B"))
        assertEquals(true, evaluator.evaluate("NOT B AND A"))
        assertEquals(true, evaluator.evaluate("B OR (A AND NOT B)"))
        assertEquals(true, evaluator.evaluate("CMAKE_BUILD_TYPE STREQUAL \"Debug\""))
        assertEquals(false, evaluator.evaluate("CMAKE_BUILD_TYPE STREQUAL \"Release\""))
        assertNull(evaluator.evaluate("UNKNOWN_VAR"))
        assertNull(evaluator.evaluate("A AND UNKNOWN_VAR"))
        assertEquals(false, evaluator.evaluate("B AND UNKNOWN_VAR"))
        assertEquals(true, evaluator.evaluate("A OR UNKNOWN_VAR"))
        assertNull(evaluator.evaluate("NIHIL_CPP_VERSION GREATER_EQUAL 26"))
        assertNull(evaluator.evaluate("CMAKE_CXX_COMPILER_ID STREQUAL \"MSVC\""))
    }

    @Test
    fun `defines from profile options and CMake truthiness`() {
        val defines = CMakeValues.definesFromOptions(
            listOf("-DCMAKE_TOOLCHAIN_FILE=F:/vcpkg/scripts/buildsystems/vcpkg.cmake", "-DNIHIL_ENABLE_PROFILER=ON", "-DNIHIL_USE_ASAN:BOOL=OFF",
                "-DCMAKE_CXX_FLAGS=\"/GR- /EHs-c-\"", "-G", "Ninja", "-DNIHIL_ENABLE_PROFILER=OFF")
        )
        assertEquals("OFF", defines["NIHIL_ENABLE_PROFILER"])
        assertEquals("OFF", defines["NIHIL_USE_ASAN"])
        assertEquals("/GR- /EHs-c-", defines["CMAKE_CXX_FLAGS"])
        assertFalse("Ninja" in defines)

        assertEquals(true, CMakeValues.truth("on"))
        assertEquals(false, CMakeValues.truth("Off"))
        assertEquals(false, CMakeValues.truth("FOO-NOTFOUND"))
        assertEquals(true, CMakeValues.truth("2"))
        assertNull(CMakeValues.truth("SOME_VAR"))
    }

    @Test
    fun `CMakeCache entries`() {
        val cache = CMakeValues.parseCache(
            """
            # This is the CMakeCache file.
            //Enable profiler
            NIHIL_ENABLE_PROFILER:BOOL=ON
            CMAKE_BUILD_TYPE:STRING=Debug
            WEIRD LINE
            EMPTY:STRING=
            """.trimIndent()
        )
        assertEquals(mapOf("NIHIL_ENABLE_PROFILER" to "ON", "CMAKE_BUILD_TYPE" to "Debug", "EMPTY" to ""), cache)
    }
}
