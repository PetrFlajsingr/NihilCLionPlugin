package cz.nihil_engine.nihil_utils_plugin.feature_flags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureFlagModelTest {

    private val src = "F:/Engine/src"
    private val cmakePath = "F:/Engine/cmake/NihilFlags.cmake"
    private val script = CMakeFlagScript.parse(CMakeFlagScriptTest.NIHIL_FLAGS)

    private fun header(root: String, bt: BuildType, text: String): List<Located<FlagDefine>> =
        CppFlagScanner.defines(text).map { Located("$src/$root/config/${bt.configFileName}", it) }

    private fun jobsHeaders(): List<Located<FlagDefine>> = BuildType.entries.flatMap { bt ->
        val fibers = if (bt == BuildType.TEST) "NIHIL_DISABLED" else "NIHIL_ENABLED"
        // Profiling doesn't define the names flag at all.
        val names = if (bt == BuildType.PROFILING) "" else "#define NIHIL_JOBS_ENABLE_NAMES NIHIL_ENABLED\n"
        header("system/Jobs", bt, "#define NIHIL_JOBS_ENABLE_FIBERS $fibers\n$names")
    }

    private fun profile(name: String, buildType: String, vararg defines: Pair<String, String>, enabled: Boolean = true, cache: Map<String, String>? = null) =
        ProfileInputs(name, enabled, buildType, mapOf(*defines), cache)

    private val profiles = listOf(
        profile("Debug", "Debug", "NIHIL_ENABLE_TESTS" to "OFF", "NIHIL_ENABLE_PROFILER" to "ON"),
        profile("Development", "Development", "NIHIL_ENABLE_PROFILER" to "OFF"),
        profile("Development 26", "Development", "NIHIL_ENABLE_PROFILER" to "ON"),
        profile("Profiling", "Profiling", cache = mapOf("NIHIL_ENABLE_PROFILER" to "ON")),
        profile("Release", "Release", enabled = false),
        profile("Test (debug)", "Debug", "NIHIL_ENABLE_TESTS" to "ON", "NIHIL_USE_MIMALLOC" to "OFF"),
    )

    private fun build(
        defines: List<Located<FlagDefine>> = jobsHeaders(),
        features: List<Located<FeatureDecl>> = emptyList(),
        profileInputs: List<ProfileInputs> = profiles,
    ) = FeatureFlagModel.build(defines, features, script, cmakePath, profileInputs, src)

    @Test
    fun `config header flags get a value per build type`() {
        val model = build()
        val fibers = model.flags.getValue("NIHIL_JOBS_ENABLE_FIBERS")
        assertEquals(FlagKind.CONFIG_HEADER, fibers.kind)
        assertEquals("system/Jobs", fibers.owner)
        assertEquals(
            listOf(CellState.ENABLED, CellState.ENABLED, CellState.ENABLED, CellState.ENABLED, CellState.DISABLED),
            BuildType.entries.map { fibers.cells.getValue(it).state },
        )
        val reason = fibers.cells.getValue(BuildType.TEST).reasons.single()
        assertEquals(SourceLink("$src/system/Jobs/config/config_test.hpp", 0), reason.link)

        val names = model.flags.getValue("NIHIL_JOBS_ENABLE_NAMES")
        assertEquals(CellState.UNDEFINED, names.cells.getValue(BuildType.PROFILING).state)
        assertEquals("✓", names.cells.getValue(BuildType.DEBUG).symbol)
    }

    @Test
    fun `ifndef guarded defaults are overridable`() {
        val defines = BuildType.entries.flatMap { bt ->
            header("scripting/Lua", bt, "#ifndef NIHIL_LUA_DEFINITIONS\n#    define NIHIL_LUA_DEFINITIONS NIHIL_ENABLED\n#endif\n")
        }
        val cell = build(defines).flags.getValue("NIHIL_LUA_DEFINITIONS").cells.getValue(BuildType.RELEASE)
        assertEquals(CellState.ENABLED, cell.state)
        assertTrue(cell.overridable)
        assertEquals("✓*", cell.symbol)
        assertTrue(cell.reasons.single().text.contains("unless defined before the header"))
    }

    @Test
    fun `profiles resolve their build type from NihilFlags cmake`() {
        val model = build()
        assertEquals(
            mapOf(
                "Debug" to BuildType.DEBUG, "Development" to BuildType.DEVELOPMENT, "Development 26" to BuildType.DEVELOPMENT,
                "Profiling" to BuildType.PROFILING, "Release" to BuildType.RELEASE, "Test (debug)" to BuildType.TEST,
            ),
            model.profiles.associate { it.name to it.buildType },
        )
        val unsupported = build(profileInputs = listOf(profile("Weird", "MinSizeRel"))).profiles.single()
        assertNull(unsupported.buildType)
    }

    @Test
    fun `cmake flags come from each profile's options, cache or option default`() {
        val model = build()
        val profiler = model.flags.getValue("NIHIL_PROFILER_ENABLED")
        assertEquals(FlagKind.CMAKE, profiler.kind)
        val cells = profiler.cells
        assertEquals(CellState.ENABLED, cells.getValue(BuildType.DEBUG).state)
        // Development and Development 26 disagree.
        assertEquals(CellState.MIXED, cells.getValue(BuildType.DEVELOPMENT).state)
        // From the profile's CMakeCache.txt.
        assertEquals(CellState.ENABLED, cells.getValue(BuildType.PROFILING).state)
        assertTrue(cells.getValue(BuildType.PROFILING).reasons.single().text.contains("CMakeCache.txt"))
        // Only a disabled profile builds Release: it's still used, and says so. The option default is OFF.
        val release = cells.getValue(BuildType.RELEASE)
        assertEquals(CellState.DISABLED, release.state)
        assertTrue(release.reasons.single().text.contains("disabled profile"))
        assertTrue(release.reasons.single().text.contains("option() default"))
        assertEquals(CellState.DISABLED, cells.getValue(BuildType.TEST).state)
        assertEquals(SourceLink(cmakePath, 11), cells.getValue(BuildType.DEBUG).reasons.single().link)

        val mimalloc = model.flags.getValue("NIHIL_MIMALLOC_ENABLED").cells
        assertEquals(CellState.ENABLED, mimalloc.getValue(BuildType.DEBUG).state)
        assertEquals(CellState.DISABLED, mimalloc.getValue(BuildType.TEST).state)
    }

    @Test
    fun `a build type without profiles is unknown rather than guessed`() {
        val model = build(profileInputs = listOf(profile("Debug", "Debug")))
        val cells = model.flags.getValue("NIHIL_ASAN_ENABLED").cells
        assertEquals(CellState.DISABLED, cells.getValue(BuildType.DEBUG).state)
        assertEquals(CellState.UNKNOWN, cells.getValue(BuildType.RELEASE).state)
        assertEquals("no CMake profile builds Release", cells.getValue(BuildType.RELEASE).reasons.single().text)
    }

    @Test
    fun `an unreadable condition makes the profile's value unknown`() {
        val odd = CMakeFlagScript.parse(
            """
            if (NIHIL_USE_THING AND CMAKE_CXX_COMPILER_ID STREQUAL "MSVC")
                add_compile_definitions(NIHIL_THING_ENABLED=NIHIL_ENABLED)
            else ()
                add_compile_definitions(NIHIL_THING_ENABLED=NIHIL_DISABLED)
            endif ()
            """.trimIndent()
        )
        val resolved = FeatureFlagModel.resolveProfile(profile("Debug", "Debug", "NIHIL_USE_THING" to "ON"), odd, cmakePath)
        assertEquals(CellState.UNKNOWN, resolved.flags.getValue("NIHIL_THING_ENABLED").state)
        val off = FeatureFlagModel.resolveProfile(profile("Debug", "Debug", "NIHIL_USE_THING" to "OFF"), odd, cmakePath)
        assertEquals(CellState.DISABLED, off.flags.getValue("NIHIL_THING_ENABLED").state)
    }

    @Test
    fun `compiler dependent and per-file source flags`() {
        val reflection = CppFlagScanner.defines(
            "#if defined(__cpp_impl_reflection)\n#define NIHIL_REFLECTION NIHIL_ENABLED\n#else\n#define NIHIL_REFLECTION NIHIL_DISABLED\n#endif\n"
        ).map { Located("$src/foundation/Macros/Public/NihilMacros/reflection.hpp", it) }
        val segmentA = CppFlagScanner.defines("#define NIHIL_ENABLE_RDG_SEGMENT_NAMES NIHIL_DISABLED\n")
            .map { Located("$src/render/RDG/Public/NihilRDG/impls/ExperimentalExecutor.ixx", it) }
        val segmentB = CppFlagScanner.defines("#define NIHIL_ENABLE_RDG_SEGMENT_NAMES NIHIL_ENABLED\n")
            .map { Located("$src/render/RDG/Public/NihilRDG/impls/ExperimentalExecutor2.ixx", it) }
        val fixed = CppFlagScanner.defines("#define NIHIL_MATSYS_EXTRA_INFO_ENABLED NIHIL_ENABLED\n")
            .map { Located("$src/render/MatSys/Public/NihilMatSys/config.hpp", it) }
        val model = build(defines = jobsHeaders() + reflection + segmentA + segmentB + fixed)

        val r = model.flags.getValue("NIHIL_REFLECTION")
        assertEquals(FlagKind.COMPILER, r.kind)
        assertTrue(r.cells.values.all { it.state == CellState.COMPILER })
        assertEquals(2, r.definitions.size)

        val segments = model.flags.getValue("NIHIL_ENABLE_RDG_SEGMENT_NAMES")
        assertEquals(CellState.MIXED, segments.cells.getValue(BuildType.DEBUG).state)
        assertEquals(CellState.ENABLED, segments.cellsFor(segmentB.single().path).getValue(BuildType.DEBUG).state)
        assertEquals(CellState.MIXED, segments.cellsFor("$src/elsewhere.cpp").getValue(BuildType.DEBUG).state)

        val extra = model.flags.getValue("NIHIL_MATSYS_EXTRA_INFO_ENABLED")
        assertEquals(FlagKind.SOURCE, extra.kind)
        assertTrue(extra.cells.values.all { it.state == CellState.ENABLED })
        assertEquals(setOf(reflection[0].path, segmentA[0].path, segmentB[0].path, fixed[0].path), model.sourceDefinitionPaths)
    }

    @Test
    fun `feature constants per build type`() {
        val features = listOf(BuildType.DEBUG to "true", BuildType.RELEASE to "false").flatMap { (bt, v) ->
            CppFlagScanner.features("inline constexpr Feature ParanoidAsserts{\"Enable paranoid assert checks\", $v};")
                .map { Located("$src/foundation/Core/config/${bt.configFileName}", it) }
        }
        val constant = build(features = features).features.single()
        assertEquals("ParanoidAsserts", constant.name)
        assertEquals("foundation/Core", constant.owner)
        assertEquals("Enable paranoid assert checks", constant.description)
        assertEquals(mapOf(BuildType.DEBUG to "true", BuildType.RELEASE to "false"), constant.values)
        assertFalse(BuildType.TEST in constant.values)
    }

    @Test
    fun `config header path parsing`() {
        assertEquals("$src/render/RHI/Backends/D3D12" to BuildType.DEVELOPMENT, FeatureFlagModel.configHeader("$src/render/RHI/Backends/D3D12/config/config_development.hpp"))
        assertNull(FeatureFlagModel.configHeader("$src/render/RHI/config/config.hpp"))
        assertNull(FeatureFlagModel.configHeader("$src/render/RHI/Public/NihilRHI/config.hpp"))
    }
}
