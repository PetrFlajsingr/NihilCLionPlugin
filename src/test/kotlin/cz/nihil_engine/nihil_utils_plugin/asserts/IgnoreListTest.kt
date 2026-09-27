package cz.nihil_engine.nihil_utils_plugin.asserts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IgnoreListTest {

    @Test
    fun `recorded target decides`() {
        val list = IgnoreList("C:/Users/me/AppData/Nihil/NihilEditorApp", emptySet(), target = "Editor")
        assertTrue(list.appliesTo("Editor"))
        assertFalse(list.appliesTo("Game"))
    }

    @Test
    fun `without a recorded target the directory name decides`() {
        val list = IgnoreList("C:/Users/me/AppData/Nihil/Game", emptySet())
        assertTrue(list.appliesTo("game"))
        assertFalse(list.appliesTo("Editor"))
    }

    @Test
    fun `every list applies when no build target is active`() {
        assertTrue(IgnoreList("C:/x/Game", emptySet(), target = "Game").appliesTo(null))
    }
}
