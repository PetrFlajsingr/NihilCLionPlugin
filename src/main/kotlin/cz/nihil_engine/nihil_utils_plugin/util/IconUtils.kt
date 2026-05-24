package cz.nihil_engine.nihil_utils_plugin.util

import com.intellij.openapi.util.IconLoader
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon

fun scaledIcon(path: String, clazz: Class<*>, logicalSize: Int = 16): Icon {
    val raw = IconLoader.getIcon(path, clazz)
    return object : Icon {
        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val w = iconWidth
            val h = iconHeight
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.translate(x, y)
                g2.scale(w.toDouble() / raw.iconWidth, h.toDouble() / raw.iconHeight)
                raw.paintIcon(c, g2, 0, 0)
            } finally {
                g2.dispose()
            }
        }

        override fun getIconWidth(): Int = JBUI.scale(logicalSize)
        override fun getIconHeight(): Int = JBUI.scale(logicalSize)
    }
}
