package cz.nihil_engine.nihil_utils_plugin.commit_checks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CommitCheckParsingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `ninja target aliases map tests to executables`() {
        val targets = NinjaTestTargets.parse(
            """
            build NihilTestCommon: phony F$:\CLionProjects\NihilEngine\build_exe\test_release\NihilTestCommon_Release.exe
            build NihilTestCommon_Release.exe: phony F$:\CLionProjects\NihilEngine\build_exe\test_release\NihilTestCommon_Release.exe
            build NihilTestRDG_vulkan: phony C$:\My$ Dir\NihilTestRDG_vulkan_Release.exe
            build NihilTestApps: phony F$:\x\NihilTestApps.exe
            build NihilCommon: phony F$:\x\NihilCommon.dll
            build NihilTestAll: phony NihilTestCommon NihilTestRDG_vulkan
            """.trimIndent().lineSequence()
        )
        assertEquals(listOf("NihilTestCommon", "NihilTestRDG_vulkan"), targets.keys.toList())
        assertEquals(File("""F:\CLionProjects\NihilEngine\build_exe\test_release\NihilTestCommon_Release.exe"""), targets["NihilTestCommon"])
        assertEquals(File("""C:\My Dir\NihilTestRDG_vulkan_Release.exe"""), targets["NihilTestRDG_vulkan"])
    }

    @Test
    fun `catch2 summaries`() {
        assertEquals(Catch2Summary(38, 0), Catch2Summary.parse("...\nAll tests passed (171 assertions in 38 test cases)\n"))
        assertEquals(Catch2Summary(14, 2), Catch2Summary.parse("Failed 2 test cases, passed 12 test cases, failed 3 assertions."))
        assertEquals(Catch2Summary(12, 1), Catch2Summary.parse("test cases:  12 |  11 passed | 1 failed\nassertions: 40 | 39 passed | 1 failed"))
        assertEquals(Catch2Summary(12, 0), Catch2Summary.parse("test cases:  12 |  11 passed | 1 skipped\nassertions: 40 | 38 passed | 2 failed"))
        assertNull(Catch2Summary.parse("Access violation"))
    }

    @Test
    fun `trailers start a paragraph`() {
        assertEquals("Fix vector\n\nTests-Passed: NihilCommon", CommitTrailers.apply("Fix vector\n", setOf("Tests-Passed"), listOf("Tests-Passed: NihilCommon")))
    }

    @Test
    fun `trailers join an existing trailer paragraph`() {
        val message = "Fix vector\n\nLonger body.\n\nCo-Authored-By: Someone <a@b.c>"
        assertEquals(
            "$message\nTests-Passed: NihilCommon",
            CommitTrailers.apply(message, setOf("Tests-Passed"), listOf("Tests-Passed: NihilCommon")),
        )
    }

    @Test
    fun `a subject line with a colon isn't a trailer paragraph`() {
        assertEquals(
            "Common: fix vector\n\nTests-Passed: NihilCommon",
            CommitTrailers.apply("Common: fix vector", setOf("Tests-Passed"), listOf("Tests-Passed: NihilCommon")),
        )
    }

    @Test
    fun `rerunning replaces the previous trailers`() {
        val keys = setOf("Tests-Passed", "Tests-Failed")
        val first = CommitTrailers.apply("Fix vector", keys, listOf("Tests-Failed: NihilCommon (build failed)"))
        assertEquals("Fix vector\n\nTests-Passed: NihilCommon", CommitTrailers.apply(first, keys, listOf("Tests-Passed: NihilCommon")))
        assertEquals("Fix vector", CommitTrailers.apply(first, keys, emptyList()))
    }

    @Test
    fun `assert ID trailers`() {
        assertEquals(listOf("Assert-IDs-Unique: 3 checked"), DuplicateAssertIdCheckinHandler.trailers(3, emptyList()))
        assertEquals(listOf("Assert-IDs-Duplicate: 0x2, 0xAB"), DuplicateAssertIdCheckinHandler.trailers(3, listOf(0xABL, 0x2L)))
        assertEquals(
            listOf("Assert-IDs-Duplicate: 0x1, 0x2, 0x3, 0x4, 0x5 and 2 more"),
            DuplicateAssertIdCheckinHandler.trailers(9, (1L..7L).toList()),
        )

        // Each check owns its keys: rerunning one leaves the other's trailers alone
        val keys = setOf(DuplicateAssertIdCheckinHandler.UNIQUE, DuplicateAssertIdCheckinHandler.DUPLICATE)
        val withTests = CommitTrailers.apply("Fix vector", setOf("Tests-Passed"), listOf("Tests-Passed: NihilCommon"))
        val duplicate = CommitTrailers.apply(withTests, keys, listOf("Assert-IDs-Duplicate: 0x2"))
        assertEquals(
            "Fix vector\n\nTests-Passed: NihilCommon\nAssert-IDs-Unique: 3 checked",
            CommitTrailers.apply(duplicate, keys, DuplicateAssertIdCheckinHandler.trailers(3, emptyList())),
        )
    }

    @Test
    fun `input hash follows content`() {
        val dir = tmp.newFolder("lib")
        val file = File(dir, "a.cpp").apply { writeText("int a;") }
        val before = InputHash.of(listOf(dir), "Test (release)|NihilTestCommon")
        assertEquals(before, InputHash.of(listOf(dir), "Test (release)|NihilTestCommon"))
        assertNotEquals(before, InputHash.of(listOf(dir), "Test (debug)|NihilTestCommon"))
        file.writeText("int b;")
        assertNotEquals(before, InputHash.of(listOf(dir), "Test (release)|NihilTestCommon"))
    }
}
