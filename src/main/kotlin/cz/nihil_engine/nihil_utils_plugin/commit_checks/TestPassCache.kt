package cz.nihil_engine.nihil_utils_plugin.commit_checks

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * Last passing run of each test target, keyed by [InputHash] of what the tests cover. Stored in the workspace
 * (PropertiesComponent) because it describes this checkout's working tree, not the project.
 */
@Service(Service.Level.PROJECT)
class TestPassCache(private val project: Project) {

    data class Pass(val hash: String, val cases: Int)

    fun get(target: String, hash: String): Pass? {
        val raw = PropertiesComponent.getInstance(project).getValue(key(target)) ?: return null
        val pass = Pass(raw.substringBefore(':'), raw.substringAfter(':', "0").toIntOrNull() ?: 0)
        return pass.takeIf { it.hash == hash }
    }

    fun record(target: String, pass: Pass) {
        PropertiesComponent.getInstance(project).setValue(key(target), "${pass.hash}:${pass.cases}")
    }

    private fun key(target: String) = "nihil.commitTests.pass.$target"

    companion object {
        fun getInstance(project: Project): TestPassCache = project.getService(TestPassCache::class.java)
    }
}
