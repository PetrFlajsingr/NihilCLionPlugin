package cz.nihil_engine.nihil_utils_plugin.scaffold

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/** Builds the dialog headlessly: catches UI DSL misuse, which only shows at runtime. */
class NewNihilModuleDialogTest : BasePlatformTestCase() {

    private lateinit var root: File

    override fun setUp() {
        super.setUp()
        root = File(project.basePath!!)
        File(root, "src/foundation/Core").mkdirs()
        File(root, "src/foundation/Core/CMakeLists.txt").writeText("nihil_library(\n NAME\n Core\n)")
        File(root, "src/foundation/Common").mkdirs()
        File(root, "src/foundation/Common/CMakeLists.txt").writeText("nihil_library(NAME Common)\nnihil_library(NAME AllocStatics)")
    }

    override fun tearDown() {
        try {
            File(root, "src").deleteRecursively()
            // CLion turns this on at startup; the framework's settings check in tearDown expects the default.
            CodeInsightSettings.getInstance().AUTO_POPUP_JAVADOC_INFO = false
        } finally {
            super.tearDown()
        }
    }

    fun `test every kind builds, validates and previews`() {
        val dialog = NewNihilModuleDialog(project)
        try {
            assertEquals("PascalCase letters and digits, e.g. SceneGraph", dialog.validationForTest())

            dialog.selectForTest(ScaffoldKind.LIBRARY, "Core")
            assertEquals("NihilEngine::Core already exists", dialog.validationForTest())

            for (kind in listOf(ScaffoldKind.LIBRARY, ScaffoldKind.INTERFACE_LIBRARY, ScaffoldKind.APP, ScaffoldKind.TOOL)) {
                dialog.selectForTest(kind, "SceneGraph")
                assertNull("$kind: ${dialog.validationForTest()}", dialog.validationForTest())
                assertFalse("$kind: ${dialog.previewForTest()}", dialog.previewForTest().startsWith("Can't render"))
            }
            assertTrue(dialog.previewForTest(), "src/tools/NihilSceneGraph/" in dialog.previewForTest())

            dialog.selectForTest(ScaffoldKind.TOOL, "NihilBaker")
            assertEquals("Leave out the Nihil prefix: the target becomes NihilNihilBaker", dialog.validationForTest())
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }
}
