package com.noisebomb.cloudflared.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.noisebomb.cloudflared.model.ConnectionColor
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon

/** Turns a [ConnectionColor] into the two things the UI needs: a swatch and a row tint. */
object ConnectionColors {

    private const val SWATCH_SIZE = 12
    private const val SWATCH_ARC = 4

    private val accents = ConnectionColor.entries.associateWith { color ->
        if (color == ConnectionColor.NONE) null else JBColor(Color(color.lightRgb), Color(color.darkRgb))
    }

    private val tints = ConnectionColor.entries.associateWith { color ->
        if (color == ConnectionColor.NONE) null else JBColor(Color(color.tintLightRgb), Color(color.tintDarkRgb))
    }

    /** The full-strength accent, for a swatch. Null for [ConnectionColor.NONE]. */
    fun accent(color: ConnectionColor): JBColor? = accents[color]

    /** The row background. Null for [ConnectionColor.NONE], which means "leave the row alone". */
    fun rowBackground(color: ConnectionColor): JBColor? = tints[color]

    fun swatch(color: ConnectionColor): Icon = SwatchIcon(accent(color))

    /** A rounded chip, or an empty outline for "None" so the combo row keeps its alignment. */
    private class SwatchIcon(private val accent: Color?) : Icon {
        override fun getIconWidth(): Int = JBUI.scale(SWATCH_SIZE)

        override fun getIconHeight(): Int = JBUI.scale(SWATCH_SIZE)

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val size = JBUI.scale(SWATCH_SIZE) - 1
                val arc = JBUI.scale(SWATCH_ARC)
                if (accent == null) {
                    g2.color = JBColor.border()
                    g2.drawRoundRect(x, y, size, size, arc, arc)
                } else {
                    g2.color = accent
                    g2.fillRoundRect(x, y, size, size, arc, arc)
                }
            } finally {
                g2.dispose()
            }
        }
    }
}
