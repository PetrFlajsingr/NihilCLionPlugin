package cz.nihil_engine.nihil_utils_plugin.base_actions

const val SELECTION_MARK: Char = ''

fun select(s: String): String = "$SELECTION_MARK$s$SELECTION_MARK"

fun cursorHere(): String = "$SELECTION_MARK$SELECTION_MARK"

data class SelectionSpec(val start: Int, val end: Int)

fun stripMarkers(text: String): Pair<String, SelectionSpec?> {
    val first = text.indexOf(SELECTION_MARK)
    if (first < 0) return text to null
    val second = text.indexOf(SELECTION_MARK, first + 1)
    if (second < 0) {
        val cleaned = text.removeRange(first, first + 1)
        return cleaned to SelectionSpec(first, first)
    }
    val cleaned = text.removeRange(second, second + 1).removeRange(first, first + 1)
    return cleaned to SelectionSpec(first, second - 1)
}
