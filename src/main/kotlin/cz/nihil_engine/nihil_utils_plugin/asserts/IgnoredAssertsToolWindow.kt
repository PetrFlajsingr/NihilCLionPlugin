package cz.nihil_engine.nihil_utils_plugin.asserts

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.tree.TreeUtil
import cz.nihil_engine.nihil_utils_plugin.project.NihilFeature
import cz.nihil_engine.nihil_utils_plugin.project.NihilProjectConfigService
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class IgnoredAssertsToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun shouldBeAvailable(project: Project): Boolean =
        NihilProjectConfigService.isEnabled(project, NihilFeature.IGNORED_ASSERTS)

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        project.messageBus.connect(toolWindow.disposable).subscribe(NihilProjectConfigService.TOPIC, NihilProjectConfigService.Listener {
            ApplicationManager.getApplication().invokeLater({
                toolWindow.isAvailable = NihilProjectConfigService.isEnabled(project, NihilFeature.IGNORED_ASSERTS)
            }, project.disposed)
        })
        val panel = IgnoredAssertsPanel(project, toolWindow.disposable)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, "", false))
    }

    companion object {
        const val ID = "Nihil Ignored Asserts"
    }
}

private class ListNode(val list: IgnoreList) : DefaultMutableTreeNode(list)

private class EntryNode(val id: Long, val list: IgnoreList, val located: List<AssertLocator.Located>?) :
    DefaultMutableTreeNode(id)

private class IgnoredAssertsPanel(
    private val project: Project,
    private val disposable: Disposable,
) : SimpleToolWindowPanel(true, true) {

    private val service = IgnoreListService.getInstance(project)
    private val root = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(root)
    private val tree = Tree(model).apply {
        isRootVisible = false
        showsRootHandles = true
        cellRenderer = Renderer()
        emptyText.text = "No ignore lists yet"
        emptyText.appendSecondaryText(
            "Run or debug a NihilEngine program: its ignored_asserts.txt is picked up from the user data directory it logs",
            SimpleTextAttributes.GRAYED_ATTRIBUTES, null,
        )
    }

    private var locations: Map<Long, List<AssertLocator.Located>>? = null
    private var locatedIds: Set<Long> = emptySet()

    init {
        setContent(ScrollPaneFactory.createScrollPane(tree))
        toolbar = ActionManager.getInstance().createActionToolbar("NihilIgnoredAsserts", toolbarActions(), true)
            .apply { targetComponent = tree }.component

        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean = navigateToSelection()
        }.installOn(tree)
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_ENTER -> if (navigateToSelection()) e.consume()
                    KeyEvent.VK_DELETE -> { stopIgnoringSelection(); e.consume() }
                }
            }
        })

        project.messageBus.connect(disposable).subscribe(IgnoreListService.TOPIC, IgnoreListService.Listener { refresh() })
        service.reloadAsync(refreshVfs = true)
        refresh()
    }

    private fun refresh(relocate: Boolean = false) {
        val ids = service.lists.flatMap { it.ids }.toSet()
        if (relocate || ids != locatedIds) locate(ids)
        rebuildTree()
    }

    private fun locate(ids: Set<Long>) {
        locatedIds = ids
        locations = null
        ReadAction.nonBlocking<Map<Long, List<AssertLocator.Located>>> { AssertLocator.locateAll(project, ids, null) }
            .expireWith(disposable)
            .coalesceBy(this)
            .finishOnUiThread(ModalityState.any()) { found ->
                if (ids == locatedIds) {
                    locations = found
                    rebuildTree()
                }
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun rebuildTree() {
        val selectedIds = selectedEntries().map { it.list.directory to it.id }.toSet()
        root.removeAllChildren()
        for (list in service.lists) {
            val listNode = ListNode(list)
            for (id in list.ids.sorted()) listNode.add(EntryNode(id, list, locations?.let { it[id].orEmpty() }))
            root.add(listNode)
        }
        model.reload()
        TreeUtil.expandAll(tree)
        TreeUtil.treeNodeTraverser(root).filter(EntryNode::class.java)
            .filter { (it.list.directory to it.id) in selectedIds }
            .forEach { tree.addSelectionPath(TreeUtil.getPathFromRoot(it)) }
    }

    private fun selectedEntries(): List<EntryNode> = tree.selectionPaths.orEmpty().mapNotNull { it.lastPathComponent as? EntryNode }

    private fun selectedList(): IgnoreList? = when (val node = tree.selectionPath?.lastPathComponent) {
        is ListNode -> node.list
        is EntryNode -> node.list
        else -> null
    }

    private fun navigateToSelection(): Boolean {
        val located = selectedEntries().singleOrNull()?.located?.firstOrNull() ?: return false
        OpenFileDescriptor(project, located.file, located.site.line, 0).navigate(true)
        return true
    }

    private fun stopIgnoringSelection() {
        val entries = selectedEntries()
        if (entries.isEmpty()) return
        val errors = entries.flatMap { service.unignore(it.id, it.list) }
        if (errors.isNotEmpty()) Messages.showErrorDialog(project, errors.joinToString("\n"), "Stop Ignoring")
    }

    private fun toolbarActions() = DefaultActionGroup().apply {
        add(object : DumbAwareAction("Refresh", "Re-read the ignore lists and locate their asserts again", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) {
                service.reloadAsync(refreshVfs = true)
                refresh(relocate = true)
            }
        })
        add(object : SelectionAction("Stop Ignoring", "Remove the selected asserts from their ignore list", AllIcons.General.Remove) {
            override fun enabled() = selectedEntries().isNotEmpty()
            override fun actionPerformed(e: AnActionEvent) = stopIgnoringSelection()
        })
        addSeparator()
        add(object : SelectionAction("Open Ignore List", "Open the selected ignored_asserts.txt in the editor", AllIcons.Actions.MenuOpen) {
            override fun enabled() = selectedList() != null
            override fun actionPerformed(e: AnActionEvent) {
                val list = selectedList() ?: return
                LocalFileSystem.getInstance().refreshAndFindFileByIoFile(list.file)?.let { OpenFileDescriptor(project, it).navigate(true) }
                    ?: Messages.showInfoMessage(project, "${list.file} doesn't exist, and it can't be created there: the user data directory may have been moved or deleted.", "Open Ignore List")
            }
        })
        add(object : SelectionAction("Forget Ignore List", "Stop tracking the selected list; the file itself is kept", AllIcons.Actions.Cancel) {
            override fun enabled() = selectedList() != null
            override fun actionPerformed(e: AnActionEvent) {
                selectedList()?.let { service.removeDirectory(it.directory) }
            }
        })
    }

    private abstract inner class SelectionAction(text: String, description: String, icon: javax.swing.Icon) :
        DumbAwareAction(text, description, icon) {
        abstract fun enabled(): Boolean
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = enabled()
        }
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private inner class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
        ) {
            when (value) {
                is ListNode -> {
                    icon = AllIcons.Nodes.Folder
                    append(value.list.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append("  ${value.list.ids.size} ignored", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append("  ${value.list.file.path}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    toolTipText = value.list.file.path
                }
                is EntryNode -> {
                    icon = AllIcons.Debugger.Db_muted_breakpoint
                    append(AssertSite.formatId(value.id))
                    val located = value.located
                    when {
                        located == null -> append("  locating…", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                        located.isEmpty() -> append("  not found in project sources", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                        else -> {
                            val first = located.first()
                            append("  ${first.site.kind.typeName}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                            append("  ${first.file.name}:${first.site.line + 1}", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES)
                            if (located.size > 1) append("  +${located.size - 1} more (duplicate ID)", SimpleTextAttributes.ERROR_ATTRIBUTES)
                        }
                    }
                    toolTipText = located?.joinToString("\n") { "${it.file.presentableUrl}:${it.site.line + 1}" }
                }
            }
        }
    }
}
