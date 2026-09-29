package cz.nihil_engine.nihil_utils_plugin.root_exclusion

import cz.nihil_engine.nihil_utils_plugin.root_exclusion.RootExclusionPlan.Wanted
import com.intellij.openapi.util.SystemInfo
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeFalse
import org.junit.Test

class RootExclusionPlanTest {

    private val base = "F:/CLionProjects/NihilSwarmGame"
    private val engineRoot = "$base/engine"
    private val linked = "F:/CLionProjects/NihilEngine"

    private fun wanted(
        engineDir: String?,
        engineRootExists: Boolean = true,
        disabled: List<String> = emptyList(),
        enabled: List<String> = emptyList(),
    ) = RootExclusionPlan.wanted(base, engineDir, engineRootExists, disabled, enabled)

    @Test
    fun `linked engine excludes engine root`() {
        assertEquals(Wanted(listOf(engineRoot)), wanted(linked))
    }

    @Test
    fun `pinned engine wants nothing`() {
        assertEquals(Wanted(emptyList()), wanted(engineRoot))
        assertEquals(Wanted(emptyList()), wanted("$engineRoot/"))
    }

    @Test
    fun `pinned engine matches case-insensitively where the file system does`() {
        assumeFalse(SystemInfo.isFileSystemCaseSensitive)
        assertEquals(Wanted(emptyList()), wanted("f:/clionprojects/nihilswarmgame/ENGINE"))
    }

    @Test
    fun `unknown engine dir keeps whatever is there`() {
        assertEquals(Wanted(emptyList(), keep = listOf(engineRoot)), wanted(null))
    }

    @Test
    fun `engine repo itself is left alone`() {
        assertEquals(Wanted(emptyList()), wanted(base))
        assertEquals(Wanted(emptyList()), wanted(linked, engineRootExists = false))
    }

    @Test
    fun `disabled generation dirs under the project are excluded`() {
        val result = wanted(
            engineRoot,
            disabled = listOf("$base/build/development", "$base/build/development-linked", "D:/elsewhere/build", "$base/build"),
            enabled = listOf("$base/build/development-linked", "$base/build/profiling-linked"),
        )
        // development-linked is enabled (CLion excludes it), D: is outside, build/ contains enabled dirs
        assertEquals(Wanted(listOf("$base/build/development")), result)
    }

    @Test
    fun `disabled generation dir containing engine root is skipped`() {
        assertEquals(Wanted(emptyList()), wanted(engineRoot, disabled = listOf(base, engineRoot)))
        assertEquals(Wanted(listOf("$engineRoot/build")), wanted(engineRoot, disabled = listOf(engineRoot, "$engineRoot/build")))
    }

    @Test
    fun `adds wanted exclusions and owns them`() {
        val changes = RootExclusionPlan.changes(Wanted(listOf(engineRoot)), excludedNow = emptyList(), owned = emptyList())
        assertEquals(RootExclusionPlan.Changes(add = listOf(engineRoot), remove = emptyList(), owned = listOf(engineRoot)), changes)
    }

    @Test
    fun `removes only its own exclusions`() {
        val manual = "$base/third_party"
        val changes = RootExclusionPlan.changes(Wanted(emptyList()), excludedNow = listOf(engineRoot, manual), owned = listOf(engineRoot))
        assertEquals(RootExclusionPlan.Changes(add = emptyList(), remove = listOf(engineRoot), owned = emptyList()), changes)
    }

    @Test
    fun `does not claim a manual exclusion it also wants`() {
        val changes = RootExclusionPlan.changes(Wanted(listOf(engineRoot)), excludedNow = listOf(engineRoot), owned = emptyList())
        assertEquals(RootExclusionPlan.Changes(add = emptyList(), remove = emptyList(), owned = emptyList()), changes)
        // ...so switching to a pinned profile leaves it excluded
        val pinned = RootExclusionPlan.changes(Wanted(emptyList()), excludedNow = listOf(engineRoot), owned = emptyList())
        assertEquals(emptyList<String>(), pinned.remove)
    }

    @Test
    fun `kept exclusion stays owned`() {
        val changes = RootExclusionPlan.changes(Wanted(emptyList(), keep = listOf(engineRoot)), excludedNow = listOf(engineRoot), owned = listOf(engineRoot))
        assertEquals(RootExclusionPlan.Changes(add = emptyList(), remove = emptyList(), owned = listOf(engineRoot)), changes)
    }

    @Test
    fun `exclusion removed by hand is forgotten`() {
        val changes = RootExclusionPlan.changes(Wanted(emptyList()), excludedNow = emptyList(), owned = listOf(engineRoot))
        assertEquals(RootExclusionPlan.Changes(add = emptyList(), remove = emptyList(), owned = emptyList()), changes)
    }

    @Test
    fun `paths compare case-insensitively like the file system`() {
        assumeFalse(SystemInfo.isFileSystemCaseSensitive)
        val changes = RootExclusionPlan.changes(Wanted(listOf(engineRoot)), excludedNow = listOf(engineRoot.uppercase()), owned = listOf(engineRoot))
        assertEquals(RootExclusionPlan.Changes(add = emptyList(), remove = emptyList(), owned = listOf(engineRoot)), changes)
    }
}
