package cz.nihil_engine.nihil_utils_plugin.cvars

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.util.concurrency.AppExecutorUtil
import cz.nihil_engine.nihil_utils_plugin.project.NihilFeature
import cz.nihil_engine.nihil_utils_plugin.project.NihilProjectConfigService

/**
 * "Go to cvar by name...": a filterable list of every indexed cvar, command and `$cvar` prefix, plus the names a
 * connected app reports (runtime-named ones included), jumping to the declaration.
 */
class GoToCVarAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = NihilProjectConfigService.isEnabled(e.project, NihilFeature.CVARS)
    }

    private data class Item(val name: String, val detail: String, val indexed: Boolean)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ReadAction.nonBlocking<List<Item>> {
            val indexed = CVarLocator.allNames(project)
            val live = CVarLiveService.getInstance(project).state.objects
            (indexed.keys + live.keys).sorted().map { name ->
                val entry = indexed[name]
                val obj = live[name]
                val kind = entry?.kind?.label ?: if (obj?.isCommand == true) "command" else "cvar"
                val detail = listOfNotNull(
                    kind,
                    obj?.value?.let { "= $it" },
                    (entry?.help ?: obj?.help)?.takeIf { it.isNotBlank() },
                    if (entry == null) "runtime" else null,
                ).joinToString("  ·  ")
                Item(name, detail, entry != null)
            }
        }
            .inSmartMode(project)
            .finishOnUiThread(ModalityState.defaultModalityState()) { items -> choose(project, items) }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun choose(project: Project, items: List<Item>) {
        if (items.isEmpty()) {
            Messages.showInfoMessage(project, "No cvars or console commands found in the project sources.", "Go to Cvar")
            return
        }
        JBPopupFactory.getInstance().createListPopup(object : BaseListPopupStep<Item>("Go to Cvar (type to filter)", items) {
            override fun getTextFor(value: Item) = "${value.name}    ${value.detail}"
            override fun isSpeedSearchEnabled() = true
            override fun onChosen(selectedValue: Item, finalChoice: Boolean): PopupStep<*>? {
                doFinalStep { navigate(project, selectedValue.name) }
                return FINAL_CHOICE
            }
        }, 25).showCenteredInCurrentWindow(project)
    }

    companion object {
        /** Opens [name]'s declaration, asking which one when several apps declare it. */
        fun navigate(project: Project, name: String) {
            ReadAction.nonBlocking<List<CVarLocation>> { CVarLocator.find(project, name) }
                .inSmartMode(project)
                .finishOnUiThread(ModalityState.defaultModalityState()) { locations ->
                    when (locations.size) {
                        0 -> if (CVarLiveService.getInstance(project).state.objects.containsKey(name)) {
                            CVarsToolWindowFactory.show(project, name)
                        } else {
                            Messages.showInfoMessage(project, "$name has no declaration with a literal name in the project sources.", "Go to Cvar")
                        }
                        1 -> open(project, locations.single())
                        else -> JBPopupFactory.getInstance().createListPopup(object : BaseListPopupStep<CVarLocation>("$name is declared in ${locations.size} places", locations) {
                            override fun getTextFor(value: CVarLocation) =
                                relativePath(project, value) + if (value.viaPrefix) "  (${value.name}.* struct)" else ""
                            override fun onChosen(selectedValue: CVarLocation, finalChoice: Boolean): PopupStep<*>? {
                                doFinalStep { open(project, selectedValue) }
                                return FINAL_CHOICE
                            }
                        }).showCenteredInCurrentWindow(project)
                    }
                }
                .submit(AppExecutorUtil.getAppExecutorService())
        }

        private fun relativePath(project: Project, location: CVarLocation): String {
            val base = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
            return base?.let { VfsUtilCore.getRelativePath(location.file, it) } ?: location.file.presentableUrl
        }

        private fun open(project: Project, location: CVarLocation) {
            OpenFileDescriptor(project, location.file, location.entry.nameOffset).navigate(true)
        }
    }
}
