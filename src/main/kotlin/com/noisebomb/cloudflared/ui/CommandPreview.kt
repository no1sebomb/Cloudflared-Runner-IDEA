package com.noisebomb.cloudflared.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.ColorUtil
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.datatransfer.StringSelection
import javax.swing.JEditorPane

/**
 * The command that will actually be spawned, shown the way a terminal would show it. A dozen rows of
 * checkboxes are hard to read back; one line of `cloudflared …` is not, which is the whole point of
 * this panel — and the copy button is there for the times the answer is "run it yourself and see".
 */
class CommandPreview : JBPanel<CommandPreview>(BorderLayout()) {

    private var command: List<String> = emptyList()

    /**
     * The font is set on the component rather than in CSS, with `HONOR_DISPLAY_PROPERTIES` so the
     * HTML inherits it. A `font-family` in the stylesheet is matched against installed families and
     * silently ignored when it does not resolve, which leaves parts of the line proportional.
     */
    private val text = JEditorPane("text/html", "").apply {
        isEditable = false
        isOpaque = false
        border = JBUI.Borders.empty()
        font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.Fonts.label().size)
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
    }

    private val copyButton = InplaceButton(
        IconButton("Copy Command", AllIcons.Actions.Copy, AllIcons.Actions.Copy),
    ) {
        CopyPasteManager.getInstance().setContents(StringSelection(command.joinToString(" ")))
        announceCopy()
    }

    /**
     * The visible box. It is a child rather than this panel itself because the outer margin has to
     * be *empty*: an opaque component paints its background across its whole bounds, border inset
     * included, so a margin painted as a border just makes the box look taller.
     */
    private val box = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        background = UIUtil.getTextFieldBackground()
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border()),
            JBUI.Borders.empty(PADDING),
        )
        add(text, BorderLayout.CENTER)
        // North inside its own panel, so the button stays put as the command wraps onto a second line.
        val buttonBox = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            add(copyButton, BorderLayout.NORTH)
        }
        add(buttonBox, BorderLayout.EAST)
    }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(MARGIN_TOP, 0, 0, MARGIN_RIGHT)
        add(box, BorderLayout.CENTER)
    }

    fun setCommand(command: List<String>) {
        this.command = command
        text.text = html(command)
        text.caretPosition = 0
        revalidate()
    }

    /**
     * A long command has to wrap inside the dialog rather than stretch it, and an HTML pane asked for
     * its preferred size answers with the width of one unbroken line. So the width is capped and the
     * height measured at that width — the standard recipe for a wrapping HTML component.
     */
    override fun getPreferredSize(): Dimension {
        val outer = insets
        val inner = box.insets
        val chrome = outer.left + outer.right + inner.left + inner.right + copyButton.preferredSize.width
        val outerWidth = if (width > 0) width else JBUI.scale(MAX_WIDTH)
        val textWidth = (outerWidth - chrome).coerceAtLeast(JBUI.scale(MIN_TEXT_WIDTH))
        text.setSize(textWidth, Short.MAX_VALUE.toInt())
        val content = maxOf(text.preferredSize.height, copyButton.preferredSize.height)
        val height = content + outer.top + outer.bottom + inner.top + inner.bottom
        return Dimension(minOf(outerWidth, JBUI.scale(MAX_WIDTH)), height)
    }

    /**
     * Flags dimmed, everything else left alone. The point is to make the shape of the command
     * scannable — where one option ends and the next begins — not to be a syntax highlighter.
     *
     * Nothing is bolded on purpose. Swing resolves a bold run by asking for the bold face of the
     * *family name*, and the bold face of logical "Monospaced" comes back proportional on some
     * systems, which put one word of the command in a different typeface than the rest.
     */
    private fun html(command: List<String>): String {
        val muted = ColorUtil.toHtmlColor(JBUI.CurrentTheme.ContextHelp.FOREGROUND)
        val body = command.joinToString(" ") { part ->
            val escaped = StringUtil.escapeXmlEntities(part)
            if (part.startsWith("--")) "<span style=\"color:$muted\">$escaped</span>" else escaped
        }
        return "<html><body>$body</body></html>"
    }

    private fun announceCopy() {
        JBPopupFactory.getInstance()
            .createHtmlTextBalloonBuilder("Command copied to clipboard", MessageType.INFO, null)
            .setFadeoutTime(FADEOUT_MILLIS)
            .createBalloon()
            .show(RelativePoint.getNorthWestOf(copyButton), Balloon.Position.above)
    }

    private companion object {
        const val PADDING = 6
        const val MARGIN_TOP = 8
        const val MARGIN_RIGHT = 12
        const val MAX_WIDTH = 460
        const val MIN_TEXT_WIDTH = 120
        const val FADEOUT_MILLIS = 2000L
    }
}
