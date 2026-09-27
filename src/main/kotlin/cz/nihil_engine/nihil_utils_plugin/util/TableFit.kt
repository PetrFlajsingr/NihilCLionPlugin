package cz.nihil_engine.nihil_utils_plugin.util

import com.intellij.util.ui.JBUI
import javax.swing.JTable

object TableFit {

    /**
     * Sizes every column to its header and widest cell (the first [sampleRows] rows), capped at [maxWidth], and
     * turns off auto-resize so a wide table scrolls instead of squeezing its columns.
     */
    fun fit(table: JTable, maxWidth: Int = 520, sampleRows: Int = 400) {
        table.autoResizeMode = JTable.AUTO_RESIZE_OFF
        val padding = JBUI.scale(16)
        for (column in 0 until table.columnCount) {
            val tableColumn = table.columnModel.getColumn(column)
            val headerRenderer = tableColumn.headerRenderer ?: table.tableHeader.defaultRenderer
            var width = headerRenderer.getTableCellRendererComponent(table, tableColumn.headerValue, false, false, -1, column).preferredSize.width
            for (row in 0 until minOf(table.rowCount, sampleRows)) {
                val renderer = table.getCellRenderer(row, column)
                width = maxOf(width, table.prepareRenderer(renderer, row, column).preferredSize.width)
            }
            tableColumn.preferredWidth = minOf(width + padding, JBUI.scale(maxWidth))
        }
    }
}
