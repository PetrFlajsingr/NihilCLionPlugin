package cz.nihil_engine.nihil_utils_plugin.asserts

import com.intellij.execution.filters.ConsoleDependentInputFilterProvider
import com.intellij.execution.filters.InputFilter
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Pair
import com.intellij.psi.search.GlobalSearchScope
import cz.nihil_engine.nihil_utils_plugin.debugger.AssertBreakService
import cz.nihil_engine.nihil_utils_plugin.project.NihilFeature
import cz.nihil_engine.nihil_utils_plugin.project.NihilProjectConfigService

class UserDataDirectoryInputFilterProvider : ConsoleDependentInputFilterProvider() {
    override fun getDefaultFilters(consoleView: ConsoleView, project: Project, scope: GlobalSearchScope): List<InputFilter> {
        val wanted = NihilProjectConfigService.isEnabled(project, NihilFeature.IGNORED_ASSERTS) ||
            NihilProjectConfigService.isEnabled(project, NihilFeature.ASSERT_BREAK_IGNORE)
        return if (wanted) listOf(UserDataDirectoryInputFilter(project, consoleView)) else emptyList()
    }
}

private class UserDataDirectoryInputFilter(private val project: Project, private val console: ConsoleView) : InputFilter {

    private val partial = StringBuilder()

    override fun applyFilter(text: String, contentType: ConsoleViewContentType): List<Pair<String, ConsoleViewContentType>>? {
        synchronized(partial) {
            partial.append(text)
            var end = partial.indexOf("\n")
            while (end >= 0) {
                check(partial.substring(0, end))
                partial.delete(0, end + 1)
                end = partial.indexOf("\n")
            }
            if (partial.length > MAX_LINE) partial.setLength(0)
        }
        return null
    }

    private fun check(line: String) {
        if (!line.contains(MARKER)) return
        val dir = IgnoreListService.USER_DATA_DIR.find(line)?.groupValues?.get(1) ?: return
        if (NihilProjectConfigService.isEnabled(project, NihilFeature.IGNORED_ASSERTS)) {
            IgnoreListService.getInstance(project).addDirectory(dir)
        }
        AssertBreakService.getInstance(project).userDataDirectoryLogged(console, dir)
    }

    companion object {
        private const val MARKER = "User data directory:"
        private const val MAX_LINE = 8192
    }
}
