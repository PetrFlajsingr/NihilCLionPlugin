package cz.nihil_engine.nihil_utils_plugin.console

import com.intellij.execution.filters.ConsoleDependentFilterProvider
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.execution.filters.LazyFileHyperlinkInfo
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import cz.nihil_engine.nihil_utils_plugin.asserts.AssertLocator
import cz.nihil_engine.nihil_utils_plugin.asserts.AssertKind
import cz.nihil_engine.nihil_utils_plugin.asserts.AssertSite
import cz.nihil_engine.nihil_utils_plugin.cvars.CVarConsoleFilter
import cz.nihil_engine.nihil_utils_plugin.project.NihilFeature
import cz.nihil_engine.nihil_utils_plugin.project.NihilProjectConfigService

/**
 * Adds [NihilConsoleFilter] to run and debug consoles of projects that enabled `console_links`, and
 * [CVarConsoleFilter] (cvar names) to those that enabled `cvars`.
 */
class NihilConsoleFilterProvider : ConsoleDependentFilterProvider() {
    override fun getDefaultFilters(consoleView: ConsoleView, project: Project, scope: GlobalSearchScope): Array<Filter> = listOfNotNull(
        NihilConsoleFilter(project).takeIf { NihilProjectConfigService.isEnabled(project, NihilFeature.CONSOLE_LINKS) },
        CVarConsoleFilter(project).takeIf { NihilProjectConfigService.isEnabled(project, NihilFeature.CVARS) },
    ).toTypedArray()
}

/**
 * Links in AssertManager's failure report (AssertManager.cpp) and in stack traces:
 *
 * ```
 * [..] [Render] [Error] Ensure a25e5483 failed      <- the ID opens the assert's source
 * file:
 *     'F:/.../Renderer.cpp'                          <- opens the file
 * line:
 *     '120'                                          <- opens the file at that line
 * stacktrace:
 * 0> F:\...\Renderer.cpp(120): NihilRender!...       <- opens file(line)
 * ```
 *
 * One instance per console, so the `file:` value can be remembered until its `line:` value arrives.
 */
class NihilConsoleFilter(private val project: Project) : Filter {

    private enum class Expect { NOTHING, FILE_VALUE, LINE_VALUE }

    private var expect = Expect.NOTHING
    private var lastFile: String? = null

    override fun applyFilter(rawLine: String, entireLength: Int): Filter.Result? {
        val lineStart = entireLength - rawLine.length
        val line = rawLine.trimEnd('\n', '\r')
        val trimmed = line.trim()
        val items = mutableListOf<Filter.ResultItem>()

        when (expect) {
            Expect.FILE_VALUE -> QUOTED.matchEntire(line)?.let { m ->
                val group = m.groups[1]!!
                lastFile = group.value
                items += item(lineStart, group.range, LazyFileHyperlinkInfo(project, group.value, 0, 0, false))
            }
            Expect.LINE_VALUE -> QUOTED.matchEntire(line)?.let { m ->
                val group = m.groups[1]!!
                val file = lastFile
                val number = group.value.toIntOrNull()
                if (file != null && number != null) {
                    items += item(lineStart, group.range, LazyFileHyperlinkInfo(project, file, number - 1, 0, false))
                }
            }
            Expect.NOTHING -> {}
        }
        expect = when (trimmed) {
            "file:" -> Expect.FILE_VALUE
            "line:" -> Expect.LINE_VALUE
            else -> Expect.NOTHING
        }

        HEADER.find(line)?.let { m ->
            val idGroup = m.groups[2]!!
            val id = AssertSite.parseId(idGroup.value)
            if (id != null) {
                lastFile = null
                items += item(lineStart, idGroup.range, HyperlinkInfo { AssertLocator.navigateToId(it, id) })
            }
        }

        for (m in STACK_FRAME.findAll(line)) {
            val path = m.groups[1]!!.value
            val number = m.groups[2]!!.value.toIntOrNull() ?: continue
            items += item(lineStart, m.range, LazyFileHyperlinkInfo(project, path, number - 1, 0, false))
        }

        return if (items.isEmpty()) null else Filter.Result(items)
    }

    private fun item(lineStart: Int, range: IntRange, info: HyperlinkInfo) =
        Filter.ResultItem(lineStart + range.first, lineStart + range.last + 1, info)

    companion object {
        private val HEADER = Regex("""\b(${AssertKind.TYPE_NAMES.joinToString("|")}) ([0-9a-fA-F]+) failed\b""")
        private val QUOTED = Regex("""^\s+'(.+)'\s*$""")
        /** `C:\path\file.cpp(123)` as printed by MSVC's std::stacktrace. */
        private val STACK_FRAME = Regex("""([A-Za-z]:[\\/][^()\r\n'"<>|?*]+?)\((\d+)\)""")
    }
}
