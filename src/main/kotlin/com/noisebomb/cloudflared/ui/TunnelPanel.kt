package com.noisebomb.cloudflared.ui

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.ui.ConsoleView
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.ClientProperty
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.render.RenderingUtil
import com.intellij.ui.table.TableView
import com.intellij.util.IconUtil
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import com.noisebomb.cloudflared.model.ConnectionConfig
import com.noisebomb.cloudflared.model.ConnectionState
import com.noisebomb.cloudflared.model.ConnectionStatus
import com.noisebomb.cloudflared.model.ConnectionType
import com.noisebomb.cloudflared.service.TunnelService
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Point
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.RGBImageFilter
import java.util.function.Supplier
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.Timer

/**
 * The whole tool window: a toolbar and table of connections on top, the log of the selected one in a
 * foldable section below.
 *
 * Registered as a [Disposable] child of the tool window, which is itself disposed with the project,
 * so consoles are released and (via [TunnelService]) processes killed when the project closes.
 */
class TunnelPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val service = TunnelService.getInstance(project)

    private val columns =
        arrayOf<ColumnInfo<ConnectionConfig, *>>(NameColumn(), TypeColumn(), RouteColumn(), StatusColumn())
    private val tableModel = ListTableModel(columns, service.connections.toMutableList())
    private val table = TableView(tableModel)

    private val consoleCards = CardLayout()
    private val consolePanel = JPanel(consoleCards)
    private val consoles = mutableMapOf<String, ConsoleView>()
    private val selectedIcons = mutableMapOf<Pair<Icon, Color>, Icon>()

    private val logHeader = LogHeader()
    private val logPanel = JPanel(BorderLayout())
    private val splitter = OnePixelSplitter(true, LOG_SPLIT_PROPORTION)

    /** Redraws the status column so the uptime counter ticks; only runs while something is up. */
    private val uptimeTimer = Timer(1000) { table.repaint() }

    /** Which button the previous click used; see [installMouseHandlers]. */
    private var lastClickButton = MouseEvent.NOBUTTON

    // One instance each, shared between the toolbar and the context menu: a shortcut is registered
    // on the action object, so a second copy of an action would be a second copy of its shortcut.
    private val runAction = RunAction()
    private val stopAction = StopAction()
    private val editAction = EditAction()
    private val duplicateAction = DuplicateAction()
    private val deleteAction = DeleteAction()
    private val moveUpAction = MoveAction(up = true)
    private val moveDownAction = MoveAction(up = false)
    private val openPublicUrlAction = OpenPublicUrlAction()
    private val copyPublicUrlAction = CopyAddressAction(isPublic = true)
    private val copyLocalUrlAction = CopyAddressAction(isPublic = false)

    private val toolbar: ActionToolbar = ActionManager.getInstance()
        .createActionToolbar(TOOLBAR_PLACE, buildToolbarGroup(), true)
        .also { it.targetComponent = table }

    private val tableComponent = JPanel(BorderLayout()).apply {
        add(toolbar.component, BorderLayout.NORTH)
        // No border anywhere in here: the tool window already provides the frame, and an extra one
        // looks nothing like the bundled tool windows.
        add(JBScrollPane(table).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
    }

    init {
        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        // Rows are selectable, cells are not. With column selection left on, a renderer is told the
        // cell is unselected even on the selected row, so it paints its icon in the unselected
        // colour against the selection background.
        table.columnSelectionAllowed = false
        table.rowSelectionAllowed = true
        table.intercellSpacing = JBUI.emptySize()
        table.setShowGrid(false)
        table.rowHeight = JBUI.scale(22)
        table.emptyText.text = "No connections"
        // Every column shares the resize, rather than the last one absorbing all the slack: with
        // AUTO_RESIZE_LAST_COLUMN the widths below would only hold until the first resize, after
        // which Status ate everything the other three did not claim.
        table.autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
        sizeColumn(NAME_COLUMN, NAME_WIDTH, minWidth = 90)
        // Two strings and an icon, and it never has anything else to say, so it stops growing.
        sizeColumn(TYPE_COLUMN, TYPE_WIDTH, minWidth = 96, maxWidth = 132)
        sizeColumn(ROUTE_COLUMN, ROUTE_WIDTH, minWidth = 120)
        sizeColumn(STATUS_COLUMN, STATUS_WIDTH, minWidth = 96)

        table.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                showConsoleFor(selected())
                toolbar.updateActionsAsync()
            }
        }
        // Without this the spinner in the Starting row is painted as a single frozen frame.
        ClientProperty.put(table, AnimatedIcon.ANIMATION_IN_RENDERER_ALLOWED, true)

        installMouseHandlers()
        registerShortcuts()
        PopupHandler.installRowSelectionTablePopup(table, buildContextMenuGroup(), POPUP_PLACE)

        consolePanel.add(placeholder(), EMPTY_CARD)
        logPanel.add(consolePanel, BorderLayout.CENTER)
        applyLogLayout()

        service.addListener(ServiceListener(), this)
        if (tableModel.rowCount > 0) table.selectionModel.setSelectionInterval(0, 0)
    }

    /**
     * A single left click follows an authorization link — the whole Status cell of a connection
     * waiting on one behaves like a link, hand cursor included. A double click edits.
     */
    private fun installMouseHandlers() {
        table.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                // Clicking past the last row deselects, the way a file list does. Without this the
                // row stays lit while nothing about the click had anything to do with it.
                if (table.rowAtPoint(e.point) < 0) table.clearSelection()
            }

            override fun mouseClicked(e: MouseEvent) {
                val previousButton = lastClickButton
                lastClickButton = e.button
                if (e.button != MouseEvent.BUTTON1) return
                if (e.clickCount == 1) {
                    linkAt(e.point)?.let { BrowserUtil.browse(it) }
                    return
                }
                // AWT counts clicks by time and position rather than by button, so the left click
                // that dismisses a context menu arrives as click two of a double click the right
                // click began. Only a pair of left clicks is one.
                if (e.clickCount == 2 && previousButton == MouseEvent.BUTTON1 && selected() != null) {
                    editConnection()
                }
            }
        })
        table.addMouseMotionListener(object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val overLink = linkAt(e.point) != null
                table.cursor = Cursor.getPredefinedCursor(
                    if (overLink) Cursor.HAND_CURSOR else Cursor.DEFAULT_CURSOR,
                )
            }
        })
    }

    /**
     * The URL under the pointer, if the cell there is painted as a link: the SSO prompt in the
     * status column, or the generated public URL in the route column.
     */
    private fun linkAt(point: Point): String? {
        val row = table.rowAtPoint(point)
        val column = table.columnAtPoint(point)
        if (row < 0 || column < 0) return null
        val config = tableModel.getItem(row) ?: return null
        val state = service.stateOf(config)
        return when (table.convertColumnIndexToModel(column)) {
            STATUS_COLUMN ->
                state.authUrl.takeIf { it.isNotBlank() && state.status == ConnectionStatus.AWAITING_AUTH }
            ROUTE_COLUMN -> routeEntry(config).takeIf { it.startsWith("http") }
            else -> null
        }
    }

    /**
     * Where you connect to reach the service in [ConnectionConfig.target]. For a quick tunnel that
     * is the hostname cloudflared generated, so it is empty until the tunnel is up; for an access
     * client it is the listener, which is known before anything runs.
     */
    private fun routeEntry(config: ConnectionConfig): String = when (config.type) {
        ConnectionType.QUICK_TUNNEL -> service.stateOf(config).publicUrl
        ConnectionType.ACCESS_TCP -> config.localBind
    }

    /**
     * [fraction] is of the whole table. Under [JTable.AUTO_RESIZE_ALL_COLUMNS] the preferred widths
     * are the ratio space is handed out in, so the fractions hold at any width; resolving them
     * against a nominal width here just makes the first paint, before any resize, sensible too.
     *
     * Order matters: `setPreferredWidth` clamps to the bounds already on the column.
     */
    private fun sizeColumn(index: Int, fraction: Double, minWidth: Int, maxWidth: Int? = null) {
        val column = table.columnModel.getColumn(index)
        column.minWidth = JBUI.scale(minWidth)
        maxWidth?.let { column.maxWidth = JBUI.scale(it) }
        column.preferredWidth = JBUI.scale((NOMINAL_TABLE_WIDTH * fraction).toInt())
    }

    private fun placeholder(): JPanel = JPanel(BorderLayout())

    private fun selected(): ConnectionConfig? = table.selectedObject

    // --- toolbar and context menu ------------------------------------------------------------

    private fun buildToolbarGroup(): DefaultActionGroup = DefaultActionGroup().apply {
        add(addGroup())
        add(deleteAction)
        add(editAction)
        addSeparator()
        add(runAction)
        add(stopAction)
        addSeparator()
        add(moveUpAction)
        add(moveDownAction)
    }

    /** The "+" button: one entry per connection type, since their forms have little in common. */
    private fun addGroup(): DefaultActionGroup = DefaultActionGroup(
        AddAction(ConnectionType.QUICK_TUNNEL),
        AddAction(ConnectionType.ACCESS_TCP),
    ).apply {
        templatePresentation.text = "Add"
        templatePresentation.description = "Add a connection"
        templatePresentation.icon = AllIcons.General.Add
        isPopup = true
    }

    private fun buildContextMenuGroup(): DefaultActionGroup = DefaultActionGroup().apply {
        add(runAction)
        add(stopAction)
        addSeparator()
        add(addressGroup())
        addSeparator()
        add(editAction)
        add(duplicateAction)
        add(deleteAction)
    }

    /** Three entries that are all "give me the address", folded away from the everyday ones. */
    private fun addressGroup(): DefaultActionGroup = DefaultActionGroup(
        openPublicUrlAction,
        copyPublicUrlAction,
        copyLocalUrlAction,
    ).apply {
        templatePresentation.text = "Addresses"
        isPopup = true
    }

    /**
     * Shortcuts live on the table, so they only fire while it has focus, and are registered on the
     * shared action instances, which is what puts the hint on toolbar tooltips and menu entries.
     */
    private fun registerShortcuts() {
        val menu = Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
        editAction.bind(KeyEvent.VK_ENTER, 0)
        deleteAction.bind(KeyEvent.VK_DELETE, 0)
        duplicateAction.bind(KeyEvent.VK_D, menu)
        runAction.bind(KeyEvent.VK_R, menu)
        stopAction.bind(KeyEvent.VK_F2, menu)
        moveUpAction.bind(KeyEvent.VK_UP, menu)
        moveDownAction.bind(KeyEvent.VK_DOWN, menu)
        openPublicUrlAction.bind(KeyEvent.VK_B, menu)
        copyPublicUrlAction.bind(KeyEvent.VK_C, menu)
        copyLocalUrlAction.bind(KeyEvent.VK_C, menu or InputEvent.SHIFT_DOWN_MASK)
    }

    private fun AnAction.bind(keyCode: Int, modifiers: Int) {
        registerCustomShortcutSet(
            CustomShortcutSet(KeyStroke.getKeyStroke(keyCode, modifiers)),
            table,
            this@TunnelPanel,
        )
    }

    // --- actions -----------------------------------------------------------------------------

    private fun addConnection(type: ConnectionType) {
        val dialog = ConnectionDialog(project, ConnectionConfig(type = type), isNew = true)
        if (!dialog.showAndGet()) return
        val added = service.addConnection(dialog.result)
        refreshRows()
        selectById(added.id)
    }

    private fun editConnection() {
        val config = selected() ?: return
        if (service.isRunning(config)) {
            Messages.showInfoMessage(project, "Stop the connection before editing it.", "Connection Is Running")
            return
        }
        val dialog = ConnectionDialog(project, config, isNew = false)
        if (!dialog.showAndGet()) return
        val index = service.indexOf(config)
        if (index >= 0) service.updateConnection(index, dialog.result)
    }

    private fun duplicateConnection() {
        val config = selected() ?: return
        val copy = config.copyOf().apply {
            id = ""
            name = config.name.takeIf { it.isNotBlank() }?.let { "$it (copy)" }.orEmpty()
        }
        val added = service.addConnection(copy, service.indexOf(config) + 1)
        refreshRows()
        selectById(added.id)
    }

    private fun removeConnection() {
        val config = selected() ?: return
        val confirmed = MessageDialogBuilder
            .yesNo("Remove Connection", "Are you sure you want to remove '${config.displayName()}' connection?")
            .yesText("Remove")
            .noText("Cancel")
            .ask(project)
        if (!confirmed) return
        service.removeConnection(config)
        consoles.remove(config.id)?.let {
            consolePanel.remove(it.component)
            Disposer.dispose(it)
        }
    }

    private fun move(up: Boolean) {
        val config = selected() ?: return
        val from = service.indexOf(config)
        service.moveConnection(from, if (up) from - 1 else from + 1)
        selectById(config.id)
    }

    /** For a quick tunnel the public address only exists once cloudflared has granted a hostname. */
    private fun publicAddress(config: ConnectionConfig): String = when (config.type) {
        ConnectionType.QUICK_TUNNEL -> service.stateOf(config).publicUrl
        ConnectionType.ACCESS_TCP -> config.target
    }

    private fun copyToClipboard(text: String) {
        if (text.isNotBlank()) CopyPasteManager.getInstance().setContents(StringSelection(text))
    }

    private inner class AddAction(private val type: ConnectionType) :
        DumbAwareAction(type.displayName, "Add a ${type.displayName.lowercase()}", CloudflaredIcons.of(type)) {
        override fun actionPerformed(e: AnActionEvent) = addConnection(type)
    }

    /**
     * The toolbar wants the minus that sits under a list, the menu wants a trash can. Same action
     * either way — two instances would mean the Delete key firing two of them.
     */
    private inner class DeleteAction : DumbAwareAction("Delete", "Remove this connection", AllIcons.General.Delete) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selected() != null
            val onToolbar = e.place == TOOLBAR_PLACE
            e.presentation.text = if (onToolbar) "Remove" else "Delete"
            e.presentation.icon = if (onToolbar) AllIcons.General.Remove else AllIcons.General.Delete
        }

        override fun actionPerformed(e: AnActionEvent) = removeConnection()
    }

    private inner class EditAction : DumbAwareAction("Edit", "Edit this connection", AllIcons.Actions.Edit) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selected() != null
        }

        override fun actionPerformed(e: AnActionEvent) = editConnection()
    }

    private inner class DuplicateAction : DumbAwareAction("Duplicate", "Copy this connection", AllIcons.Actions.Copy) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selected() != null
        }

        override fun actionPerformed(e: AnActionEvent) = duplicateConnection()
    }

    /** Never dead: restarts whatever is already up. */
    private inner class RunAction : DumbAwareAction("Run", "Start this connection", AllIcons.Actions.Execute) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            val config = selected()
            e.presentation.isEnabled = config != null
            if (config != null && service.isRunning(config)) {
                e.presentation.text = "Restart"
                e.presentation.icon = AllIcons.Actions.Restart
            } else {
                e.presentation.text = "Run"
                e.presentation.icon = AllIcons.Actions.Execute
            }
        }

        override fun actionPerformed(e: AnActionEvent) {
            selected()?.let { service.restart(it) }
        }
    }

    private inner class StopAction : DumbAwareAction("Stop", "Stop this connection", AllIcons.Actions.Suspend) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            val config = selected()
            e.presentation.isEnabled = config != null && service.isRunning(config)
        }

        override fun actionPerformed(e: AnActionEvent) {
            selected()?.let { service.stop(it) }
        }
    }

    private inner class MoveAction(private val up: Boolean) : DumbAwareAction(
        if (up) "Move Up" else "Move Down",
        if (up) "Move this connection up" else "Move this connection down",
        if (up) AllIcons.Actions.MoveUp else AllIcons.Actions.MoveDown,
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            val index = selected()?.let { service.indexOf(it) } ?: -1
            e.presentation.isEnabled =
                if (up) index > 0 else index in 0 until service.connections.size - 1
        }

        override fun actionPerformed(e: AnActionEvent) = move(up)
    }

    /**
     * Only a quick tunnel has a public address you can open: an Access hostname is a bare host that
     * a browser cannot do anything useful with. The entry stays in the menu, disabled, so both
     * connection types show the same list.
     */
    private inner class OpenPublicUrlAction :
        DumbAwareAction("Open Public URL", "Open the tunnel in a browser", AllIcons.General.Web) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        private fun url(): String = selected()
            ?.let { publicAddress(it) }
            ?.takeIf { it.startsWith("http") }
            .orEmpty()

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = url().isNotBlank()
        }

        override fun actionPerformed(e: AnActionEvent) {
            url().takeIf { it.isNotBlank() }?.let { BrowserUtil.browse(it) }
        }
    }

    private inner class CopyAddressAction(private val isPublic: Boolean) : DumbAwareAction(
        if (isPublic) "Copy Public URL" else "Copy Local URL",
        if (isPublic) "Copy the address the tunnel is reachable at" else "Copy the address on this machine",
        AllIcons.Actions.Copy,
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        private fun address(): String = selected()
            ?.let { if (isPublic) publicAddress(it) else it.localAddress() }
            .orEmpty()

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = address().isNotBlank()
        }

        override fun actionPerformed(e: AnActionEvent) = copyToClipboard(address())
    }

    private inner class ClearLogAction :
        DumbAwareAction("Clear Log", "Clear the log of the selected connection", AllIcons.Actions.GC) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selected()?.let { consoles.containsKey(it.id) } == true
        }

        override fun actionPerformed(e: AnActionEvent) {
            selected()?.let { consoles[it.id]?.clear() }
        }
    }

    // --- log section -------------------------------------------------------------------------

    /**
     * The platform's collapsible group header lives in an `impl` package, so this rebuilds it: a
     * titled separator whose label doubles as the fold toggle, with its own actions on the right.
     */
    private inner class LogHeader : JPanel(BorderLayout()) {
        private val separator = TitledSeparator("Log")

        init {
            border = JBUI.Borders.empty(2, 6, 2, 4)
            isOpaque = false
            separator.border = JBUI.Borders.empty()
            separator.isOpaque = false
            separator.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            separator.label.iconTextGap = JBUI.scale(4)
            val toggle = object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) = toggleLog()
            }
            separator.addMouseListener(toggle)
            separator.label.addMouseListener(toggle)

            val actions = ActionManager.getInstance()
                .createActionToolbar(LOG_TOOLBAR_PLACE, DefaultActionGroup(ClearLogAction()), true)
            actions.targetComponent = table
            actions.component.isOpaque = false

            add(separator, BorderLayout.CENTER)
            add(actions.component, BorderLayout.EAST)
            refreshIcon()
        }

        fun refreshIcon() {
            separator.label.icon =
                if (service.logExpanded) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
        }
    }

    private fun toggleLog() {
        service.logExpanded = !service.logExpanded
        logHeader.refreshIcon()
        applyLogLayout()
    }

    /**
     * Collapsing has to take the log out of the splitter entirely — leaving it in would keep the
     * divider draggable over an invisible component.
     */
    private fun applyLogLayout() {
        removeAll()
        // Splitter.setFirstComponent short-circuits when the component is unchanged, so re-expanding
        // would leave the table parented to this panel and the splitter empty. Clear it first.
        splitter.firstComponent = null
        splitter.secondComponent = null
        if (service.logExpanded) {
            logPanel.add(logHeader, BorderLayout.NORTH)
            splitter.firstComponent = tableComponent
            splitter.secondComponent = logPanel
            add(splitter, BorderLayout.CENTER)
        } else {
            add(tableComponent, BorderLayout.CENTER)
            add(logHeader, BorderLayout.SOUTH)
        }
        revalidate()
        repaint()
    }

    private fun consoleFor(config: ConnectionConfig): ConsoleView = consoles.getOrPut(config.id) {
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        Disposer.register(this, console)
        consolePanel.add(console.component, config.id)
        console
    }

    private fun showConsoleFor(config: ConnectionConfig?) {
        if (config == null) {
            consoleCards.show(consolePanel, EMPTY_CARD)
        } else {
            consoleFor(config)
            consoleCards.show(consolePanel, config.id)
        }
    }

    // --- table plumbing ----------------------------------------------------------------------

    private fun selectById(id: String) {
        val row = tableModel.items.indexOfFirst { it.id == id }
        if (row >= 0) table.selectionModel.setSelectionInterval(row, row)
    }

    private fun refreshRows() {
        val previous = selected()?.id
        tableModel.items = service.connections.toMutableList()
        if (previous != null) selectById(previous)
        showConsoleFor(selected())
        toolbar.updateActionsAsync()
    }

    /**
     * Single-colour icons keep their own colour on a selected row, where it all but disappears
     * against the selection background. Repaint them in the row's foreground colour instead —
     * cached, because the uptime counter repaints the table every second.
     *
     * The colour comes from the table rather than from the renderer's own `foreground`, and the
     * selection from the row rather than the cell, so neither depends on the platform agreeing that
     * this particular cell is selected.
     *
     * `IconUtil.colorize` is the obvious call and the wrong one: it *multiplies* the source
     * brightness by the target's, so recolouring a mid-grey icon white leaves it mid-grey.
     */
    private fun rowIcon(base: Icon, table: JTable, row: Int): Icon {
        if (!isRowSelected(table, row)) return base
        val foreground = RenderingUtil.getForeground(table, true)
        return selectedIcons.getOrPut(base to foreground) {
            IconUtil.filterIcon(base, RecolorFilter(foreground), table)
        }
    }

    private fun isRowSelected(table: JTable, row: Int): Boolean = row in 0 until table.rowCount &&
        table.isRowSelected(row)

    /**
     * Keeps each pixel's alpha — that is the anti-aliasing — and replaces the colour outright.
     *
     * A plain [java.util.function.Supplier] rather than the platform's `RgbImageFilterSupplier`:
     * that interface and `IconLoader.filterIcon` are both `@ApiStatus.Internal`, and the Plugin
     * Verifier fails the build over them. `IconUtil.filterIcon` is the public equivalent.
     */
    private class RecolorFilter(private val color: Color) : Supplier<RGBImageFilter> {
        override fun get(): RGBImageFilter = object : RGBImageFilter() {
            private val replacement = color.rgb and RGB_MASK

            init {
                canFilterIndexColorModel = true
            }

            override fun filterRGB(x: Int, y: Int, rgb: Int): Int = (rgb and ALPHA_MASK) or replacement
        }

        private companion object {
            const val RGB_MASK = 0x00FFFFFF
            const val ALPHA_MASK = 0xFF000000.toInt()
        }
    }

    private fun syncUptimeTimer() {
        val ticking = service.connections.any { service.stateOf(it).status == ConnectionStatus.RUNNING }
        if (ticking && !uptimeTimer.isRunning) {
            uptimeTimer.start()
        } else if (!ticking && uptimeTimer.isRunning) {
            uptimeTimer.stop()
        }
    }

    private inner class ServiceListener : TunnelService.Listener {
        override fun processCreated(config: ConnectionConfig, handler: ProcessHandler) {
            // Attach before startNotify() so the first lines — including the generated URL — are kept.
            val console = consoleFor(config)
            console.clear()
            console.attachToProcess(handler)
        }

        override fun stateChanged(config: ConnectionConfig, state: ConnectionState) {
            val row = tableModel.items.indexOfFirst { it.id == config.id }
            if (row >= 0) tableModel.fireTableRowsUpdated(row, row)
            syncUptimeTimer()
            toolbar.updateActionsAsync()
        }

        override fun connectionsChanged() {
            refreshRows()
            syncUptimeTimer()
        }
    }

    override fun dispose() {
        uptimeTimer.stop()
        // Consoles are disposed as children of this panel; processes belong to the project service.
        consoles.clear()
    }

    // --- columns -----------------------------------------------------------------------------

    /**
     * Everything the three columns need to look like one selected row rather than three cells.
     *
     * `setPaintFocusBorder` and the icon background are both decided before `customizeCellRenderer`
     * runs, which is why the icon flag is set once in the constructor and the focus border is turned
     * off on every pass.
     */
    private abstract class RowRenderer : ColoredTableCellRenderer() {
        init {
            // Otherwise the strip behind the icon is filled with the *table's* background, punching
            // a hole in the selection colour the rest of the cell is painted with.
            isTransparentIconBackground = true
        }

        /**
         * The row is the unit of selection, so the ring the platform draws around the one focused
         * *cell* is noise — and it outlives the selection colour when the table loses focus, which
         * leaves an outlined row on an otherwise unhighlighted table.
         *
         * Only the border reads `hasFocus`; foreground and background come from `selected` alone,
         * so lying about it here costs nothing else.
         */
        final override fun acquireState(table: JTable, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
            super.acquireState(table, selected, false, row, column)
        }

        final override fun customizeCellRenderer(
            table: JTable,
            value: Any?,
            selected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ) {
            val config = value as? ConnectionConfig ?: return
            // The tag colour is the row's background, so it has to lose to the selection colour.
            if (!selected) {
                ConnectionColors.rowBackground(config.color)?.let { background = it }
            }
            render(table, config, row)
        }

        abstract fun render(table: JTable, config: ConnectionConfig, row: Int)
    }

    private class NameColumn : ColumnInfo<ConnectionConfig, ConnectionConfig>("Name") {
        override fun valueOf(item: ConnectionConfig): ConnectionConfig = item

        override fun getRenderer(item: ConnectionConfig) = object : RowRenderer() {
            override fun render(table: JTable, config: ConnectionConfig, row: Int) {
                append(config.displayName())
            }
        }
    }

    private inner class TypeColumn : ColumnInfo<ConnectionConfig, ConnectionConfig>("Type") {
        override fun valueOf(item: ConnectionConfig): ConnectionConfig = item

        override fun getRenderer(item: ConnectionConfig) = object : RowRenderer() {
            override fun render(table: JTable, config: ConnectionConfig, row: Int) {
                icon = rowIcon(CloudflaredIcons.of(config.type), table, row)
                append(config.type.displayName)
            }
        }
    }

    /**
     * Which service this connection reaches and the address that reaches it, always in that order.
     * The two types run the traffic in opposite directions — a quick tunnel carries the public in,
     * an access client dials out — so labelling the ends "from" and "to" would mean the opposite
     * thing on neighbouring rows. Service first, entry point second, holds for both.
     */
    private inner class RouteColumn : ColumnInfo<ConnectionConfig, ConnectionConfig>("Route") {
        override fun valueOf(item: ConnectionConfig): ConnectionConfig = item

        override fun getRenderer(item: ConnectionConfig) = object : RowRenderer() {
            override fun render(table: JTable, config: ConnectionConfig, row: Int) {
                val entry = routeEntry(config)
                append(config.target)
                append(ROUTE_ARROW, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                when {
                    entry.isBlank() -> append(NO_ENTRY_POINT, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    // Only a quick tunnel's generated hostname is a URL a browser can open; the
                    // mouse handler opens whatever this branch paints as a link.
                    entry.startsWith("http") -> append(entry, SimpleTextAttributes.LINK_ATTRIBUTES)
                    else -> append(entry)
                }
                // The column is the first thing squeezed in a narrow tool window, and either end of
                // a route is long enough to be clipped there.
                toolTipText = "${config.target}$ROUTE_ARROW${entry.ifBlank { NO_ENTRY_POINT }}"
            }
        }
    }

    /** Status, uptime and the one useful address, so a failure is obvious without opening the log. */
    private inner class StatusColumn : ColumnInfo<ConnectionConfig, ConnectionConfig>("Status") {
        override fun valueOf(item: ConnectionConfig): ConnectionConfig = item

        override fun getRenderer(item: ConnectionConfig) = object : RowRenderer() {
            override fun render(table: JTable, config: ConnectionConfig, row: Int) {
                val state = service.stateOf(config)
                val statusIcon = CloudflaredIcons.of(state.status)
                icon = if (CloudflaredIcons.isMonochrome(state.status)) {
                    rowIcon(statusIcon, table, row)
                } else {
                    statusIcon
                }
                if (state.status == ConnectionStatus.AWAITING_AUTH && state.authUrl.isNotBlank()) {
                    // Clicking the cell opens this; see the mouse handlers on the table.
                    append("${state.status.label} $EXTERNAL_LINK_ARROW", SimpleTextAttributes.LINK_ATTRIBUTES)
                    return
                }
                val attributes = when (state.status) {
                    ConnectionStatus.RUNNING -> SimpleTextAttributes.REGULAR_ATTRIBUTES
                    ConnectionStatus.AWAITING_AUTH -> SimpleTextAttributes.REGULAR_ATTRIBUTES
                    ConnectionStatus.FAILED -> SimpleTextAttributes.ERROR_ATTRIBUTES
                    else -> SimpleTextAttributes.GRAYED_ATTRIBUTES
                }
                append(state.status.label, attributes)
                state.uptime().takeIf { it.isNotEmpty() }?.let {
                    append(" $it", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                // Addresses used to live here; only the things you cannot get anywhere else remain.
                if (state.detail.isNotBlank()) {
                    append("  ${state.detail}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                if (state.warning.isNotBlank()) {
                    append("  ${state.warning}", WARNING_ATTRIBUTES)
                }
                // No longer the column that soaks up the leftover width, and a detail or a warning
                // is long enough to be clipped by the share it does get.
                toolTipText = getCharSequence(false).toString().trim().ifBlank { null }
            }
        }
    }

    private companion object {
        const val EMPTY_CARD = "__empty__"
        const val TOOLBAR_PLACE = "CloudflaredRunnerToolbar"
        const val LOG_TOOLBAR_PLACE = "CloudflaredRunnerLogToolbar"
        const val POPUP_PLACE = "CloudflaredRunnerPopup"
        const val NAME_COLUMN = 0
        const val TYPE_COLUMN = 1
        const val ROUTE_COLUMN = 2
        const val STATUS_COLUMN = 3
        const val ROUTE_ARROW = " \u2192 "

        /** A quick tunnel has no public hostname until cloudflared prints one. */
        const val NO_ENTRY_POINT = "\u2014"
        const val EXTERNAL_LINK_ARROW = "\u2197"
        const val LOG_SPLIT_PROPORTION = 0.72f

        /** Fractions of the table each column gets. They sum to one; see `sizeColumn`. */
        const val NAME_WIDTH = 0.24
        const val TYPE_WIDTH = 0.16
        const val ROUTE_WIDTH = 0.38
        const val STATUS_WIDTH = 0.22

        /** The width the fractions are resolved against for the very first paint. */
        const val NOMINAL_TABLE_WIDTH = 720

        /** Amber, not red: whatever it says, the connection is still up. */
        val WARNING_ATTRIBUTES = SimpleTextAttributes(
            SimpleTextAttributes.STYLE_PLAIN,
            JBColor.namedColor("Component.warningForeground", 0x9A6E3A, 0xD9A343),
        )
    }
}
