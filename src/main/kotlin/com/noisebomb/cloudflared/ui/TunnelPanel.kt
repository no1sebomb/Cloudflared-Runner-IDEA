package com.noisebomb.cloudflared.ui

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.ui.ConsoleView
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.TableView
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
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants

/**
 * The whole tool window: a table of connections on top, the log of the selected one below.
 *
 * Registered as a [Disposable] child of the tool window, which is itself disposed with the project,
 * so consoles are released and (via [TunnelService]) processes killed when the project closes.
 */
class TunnelPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val service = TunnelService.getInstance(project)

    private val columns = arrayOf<ColumnInfo<ConnectionConfig, *>>(
        object : ColumnInfo<ConnectionConfig, String>("Name") {
            override fun valueOf(item: ConnectionConfig): String = item.displayName()
        },
        object : ColumnInfo<ConnectionConfig, String>("Type") {
            override fun valueOf(item: ConnectionConfig): String = item.type.displayName
        },
        StatusColumn(),
    )

    private val tableModel = ListTableModel(columns, service.connections.toMutableList())
    private val table = TableView(tableModel)

    private val consoleCards = CardLayout()
    private val consolePanel = JPanel(consoleCards)
    private val consoles = mutableMapOf<String, ConsoleView>()

    init {
        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        table.setShowGrid(false)
        table.rowHeight = JBUI.scale(22)
        table.selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) showConsoleFor(selected()) }
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val config = selected() ?: return false
                if (service.isRunning(config)) service.stop(config) else service.start(config)
                return true
            }
        }.installOn(table)

        consolePanel.add(placeholder(), EMPTY_CARD)

        val splitter = OnePixelSplitter(true, 0.35f).apply {
            firstComponent = buildTableComponent()
            secondComponent = consolePanel
        }
        add(splitter, BorderLayout.CENTER)

        service.addListener(ServiceListener(), this)
        if (tableModel.rowCount > 0) table.selectionModel.setSelectionInterval(0, 0)
    }

    private fun buildTableComponent(): JPanel = ToolbarDecorator.createDecorator(table)
        .setAddAction { addConnection() }
        .setEditAction { editConnection() }
        .setRemoveAction { removeConnection() }
        .addExtraAction(StartAction())
        .addExtraAction(StopAction())
        .addExtraAction(CopyStatusAction())
        .createPanel()

    private fun placeholder(): JPanel = JPanel(BorderLayout()).apply {
        add(JBLabel("Select a connection to see its output.", SwingConstants.CENTER), BorderLayout.CENTER)
    }

    private fun selected(): ConnectionConfig? = table.selectedObject

    // --- actions ---------------------------------------------------------------------------

    private fun addConnection() {
        val dialog = ConnectionDialog(project, ConnectionConfig(type = ConnectionType.QUICK_TUNNEL), isNew = true)
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
        val index = service.connections.indexOfFirst { it.id == config.id }
        if (index >= 0) service.updateConnection(index, dialog.result)
    }

    private fun removeConnection() {
        val config = selected() ?: return
        service.removeConnection(config)
        consoles.remove(config.id)?.let { Disposer.dispose(it) }
    }

    private inner class StartAction : DumbAwareAction("Start", "Start this connection", AllIcons.Actions.Execute) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            val config = selected()
            e.presentation.isEnabled = config != null && !service.isRunning(config)
        }

        override fun actionPerformed(e: AnActionEvent) {
            selected()?.let { service.start(it) }
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

    private inner class CopyStatusAction :
        DumbAwareAction("Copy Status", "Copy the URL or bind address", AllIcons.Actions.Copy) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selected()?.let { service.stateOf(it).detail.isNotBlank() } == true
        }

        override fun actionPerformed(e: AnActionEvent) {
            val config = selected() ?: return
            CopyPasteManager.getInstance().setContents(StringSelection(service.stateOf(config).detail))
        }
    }

    // --- console ---------------------------------------------------------------------------

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

    private fun selectById(id: String) {
        val row = tableModel.items.indexOfFirst { it.id == id }
        if (row >= 0) table.selectionModel.setSelectionInterval(row, row)
    }

    private fun refreshRows() {
        val previous = selected()?.id
        tableModel.items = service.connections.toMutableList()
        if (previous != null) selectById(previous)
        showConsoleFor(selected())
    }

    private inner class ServiceListener : TunnelService.Listener {
        override fun processCreated(config: ConnectionConfig, handler: ProcessHandler) {
            // Attach before startNotify() so the first lines — including the generated URL — are kept.
            val console = consoleFor(config)
            console.clear()
            console.attachToProcess(handler)
            showConsoleFor(config)
        }

        override fun stateChanged(config: ConnectionConfig, state: ConnectionState) {
            val row = tableModel.items.indexOfFirst { it.id == config.id }
            if (row >= 0) tableModel.fireTableRowsUpdated(row, row)
        }

        override fun connectionsChanged() {
            refreshRows()
        }
    }

    override fun dispose() {
        // Consoles are disposed as children of this panel; processes belong to the project service.
        consoles.clear()
    }

    /** Status text plus a colour cue, so a failure is obvious without opening the log. */
    private inner class StatusColumn : ColumnInfo<ConnectionConfig, ConnectionConfig>("Status") {
        override fun valueOf(item: ConnectionConfig): ConnectionConfig = item

        override fun getRenderer(item: ConnectionConfig) = object : ColoredTableCellRenderer() {
            override fun customizeCellRenderer(
                table: JTable,
                value: Any?,
                selected: Boolean,
                hasFocus: Boolean,
                row: Int,
                column: Int,
            ) {
                val config = value as? ConnectionConfig ?: return
                val state = service.stateOf(config)
                val attributes = when (state.status) {
                    ConnectionStatus.RUNNING -> SimpleTextAttributes.REGULAR_ATTRIBUTES
                    ConnectionStatus.FAILED -> SimpleTextAttributes.ERROR_ATTRIBUTES
                    else -> SimpleTextAttributes.GRAYED_ATTRIBUTES
                }
                append(state.detail.ifBlank { state.status.name.lowercase() }, attributes)
            }
        }
    }

    private companion object {
        const val EMPTY_CARD = "__empty__"
    }
}
