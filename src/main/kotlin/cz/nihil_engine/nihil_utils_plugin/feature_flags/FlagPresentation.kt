package cz.nihil_engine.nihil_utils_plugin.feature_flags

import com.intellij.openapi.util.text.StringUtil

/** What the lens shows for a flag: the inlay's segments and the popup explaining them. */
object FlagPresentation {

    data class Segment(val text: String, val buildType: BuildType?, val state: CellState?, val active: Boolean)

    /** Cells for a use in [path]; for a CMake flag the active build type shows the active profile's own value. */
    fun effectiveCells(info: FlagInfo, path: String?, active: BuildType?, activeProfile: ResolvedProfile?): Map<BuildType, FlagCell> {
        val cells = info.cellsFor(path)
        if (info.kind != FlagKind.CMAKE || active == null || activeProfile?.buildType != active) return cells
        val value = activeProfile.flags[info.name] ?: return cells
        return cells + (active to FlagCell(value.state, listOf(CellReason("${activeProfile.name} (active profile): ${value.state.description} — ${value.reason}", value.link))))
    }

    fun segments(info: FlagInfo?, cells: Map<BuildType, FlagCell>?, active: BuildType?): List<Segment> = when {
        info == null || cells == null -> listOf(Segment("? no definition found", null, CellState.UNKNOWN, false))
        info.kind == FlagKind.COMPILER -> listOf(Segment("compiler-dependent", null, CellState.COMPILER, false))
        else -> BuildType.entries.map { bt ->
            val cell = cells.getValue(bt)
            Segment(bt.short + cell.symbol, bt, cell.state, bt == active)
        }
    }

    fun inlayText(segments: List<Segment>): String = segments.joinToString(" ") { it.text }

    /**
     * Popup HTML. Links are `<a href="N">` where N indexes [links], which this fills; `<a href="matrix">` opens
     * the tool window.
     */
    fun html(
        name: String,
        info: FlagInfo?,
        cells: Map<BuildType, FlagCell>?,
        active: BuildType?,
        activeProfile: ResolvedProfile?,
        links: MutableList<SourceLink>,
    ): String = buildString {
        fun esc(s: String) = StringUtil.escapeXmlEntities(s)
        fun link(target: SourceLink?, text: String): String {
            if (target == null) return esc(text)
            links += target
            return "<a href=\"${links.size - 1}\">${esc(text)}</a>"
        }

        append("<html><body>")
        append("<b>").append(esc(name)).append("</b>")
        if (info == null || cells == null) {
            append("<br>No definition found in config headers, cmake/NihilFlags.cmake or other sources.")
            append("<br><br><a href=\"matrix\">Show all flags</a></body></html>")
            return@buildString
        }
        append(" &nbsp;<span style=\"color:gray\">").append(esc(info.kind.label)).append(", ").append(esc(info.owner)).append("</span>")
        info.note?.let { append("<br><i>").append(esc(it)).append("</i>") }

        if (info.kind == FlagKind.COMPILER) {
            append("<br>")
            cells.values.first().reasons.forEach { append("<br>").append(link(it.link, it.text)) }
        } else {
            append("<table cellpadding=\"1\" cellspacing=\"0\">")
            for (bt in BuildType.entries) {
                val cell = cells.getValue(bt)
                val label = bt.displayName + if (bt == active) " (active)" else ""
                append("<tr><td valign=\"top\">")
                append(if (bt == active) "<b>${esc(label)}</b>" else esc(label))
                append("</td><td valign=\"top\">&nbsp;").append(esc(cell.symbol)).append("&nbsp;</td><td valign=\"top\">")
                append(cell.reasons.joinToString("<br>") { link(it.link, it.text) }.ifEmpty { esc(cell.state.description) })
                append("</td></tr>")
            }
            append("</table>")
            if (cells.values.any { it.overridable }) append("<br>* default: a build can define the flag before including the header")
        }

        if (info.kind == FlagKind.CMAKE) {
            append("<br>Set per CMake profile, not per build type. ")
            append(if (activeProfile != null) "Active profile: <b>${esc(activeProfile.name)}</b> (${esc(activeProfile.buildType?.displayName ?: "unknown build type")})." else "No active CMake profile.")
        }
        append("<br><br><a href=\"matrix\">Show all flags</a></body></html>")
    }
}
