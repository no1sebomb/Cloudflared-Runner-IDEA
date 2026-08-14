package com.noisebomb.cloudflared.service

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.util.xmlb.annotations.XCollection
import com.noisebomb.cloudflared.model.ConnectionConfig
import com.noisebomb.cloudflared.model.ConnectionState
import com.noisebomb.cloudflared.model.ConnectionStatus
import com.noisebomb.cloudflared.model.ConnectionType
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.SwingUtilities

/**
 * Owns the connection list and the child processes. Disposed with the project, which kills every
 * running `cloudflared` so nothing leaks when the project window closes.
 */
@Service(Service.Level.PROJECT)
@State(name = "CloudflaredRunner", storages = [Storage("cloudflaredRunner.xml")])
class TunnelService(private val project: Project) : PersistentStateComponent<TunnelService.State>, Disposable {

    class State {
        @XCollection(style = XCollection.Style.v2)
        @JvmField
        var connections: MutableList<ConnectionConfig> = mutableListOf()

        /** Escape hatch for a `cloudflared` that is not on PATH. */
        @JvmField
        var executable: String = DEFAULT_EXECUTABLE
    }

    interface Listener {
        /** Fired before `startNotify()` so a console can attach without losing early output. */
        fun processCreated(config: ConnectionConfig, handler: ProcessHandler) {}
        fun stateChanged(config: ConnectionConfig, state: ConnectionState) {}
        fun connectionsChanged() {}
    }

    private var state = State()
    private val handlers = ConcurrentHashMap<String, ProcessHandler>()
    private val states = ConcurrentHashMap<String, ConnectionState>()
    private val listeners = CopyOnWriteArrayList<Listener>()

    override fun getState(): State = state

    override fun loadState(loaded: State) {
        XmlSerializerUtil.copyBean(loaded, state)
        state.connections.forEach { if (it.id.isBlank()) it.id = UUID.randomUUID().toString() }
    }

    // --- connection list -------------------------------------------------------------------

    val connections: List<ConnectionConfig> get() = state.connections.toList()

    var executable: String
        get() = state.executable.ifBlank { DEFAULT_EXECUTABLE }
        set(value) {
            state.executable = value.ifBlank { DEFAULT_EXECUTABLE }
        }

    fun addConnection(config: ConnectionConfig): ConnectionConfig {
        if (config.id.isBlank()) config.id = UUID.randomUUID().toString()
        state.connections.add(config)
        fire { it.connectionsChanged() }
        return config
    }

    fun updateConnection(index: Int, config: ConnectionConfig) {
        state.connections[index] = config
        fire { it.connectionsChanged() }
    }

    fun removeConnection(config: ConnectionConfig) {
        stop(config)
        state.connections.removeIf { it.id == config.id }
        states.remove(config.id)
        fire { it.connectionsChanged() }
    }

    // --- process control -------------------------------------------------------------------

    fun stateOf(config: ConnectionConfig): ConnectionState = states[config.id] ?: ConnectionState()

    fun isRunning(config: ConnectionConfig): Boolean = handlers[config.id]?.isProcessTerminated == false

    fun start(config: ConnectionConfig) {
        if (isRunning(config)) return

        val commandLine = GeneralCommandLine(config.commandLine(executable))
            .withCharset(StandardCharsets.UTF_8)
            .withWorkDirectory(project.basePath)

        val handler = try {
            KillableColoredProcessHandler(commandLine)
        } catch (e: ExecutionException) {
            thisLogger().info("Failed to start cloudflared", e)
            setState(config, ConnectionState(ConnectionStatus.FAILED, e.message ?: "Failed to start cloudflared"))
            return
        }

        handlers[config.id] = handler
        handler.addProcessListener(StatusListener(config))
        fire { it.processCreated(config, handler) }

        setState(
            config,
            when (config.type) {
                // Nothing useful to show until cloudflared prints the generated hostname.
                ConnectionType.QUICK_TUNNEL -> ConnectionState(ConnectionStatus.STARTING, "Requesting tunnel…")
                // The bind address is known up front; that is the whole status for an access client.
                ConnectionType.ACCESS_TCP -> ConnectionState(ConnectionStatus.RUNNING, config.localBind)
            },
        )
        handler.startNotify()
    }

    fun stop(config: ConnectionConfig) {
        handlers[config.id]?.destroyProcess()
    }

    fun stopAll() {
        handlers.values.forEach { it.destroyProcess() }
    }

    /** Streams output looking for the few lines that carry status. Called off the EDT. */
    private inner class StatusListener(private val config: ConnectionConfig) : ProcessListener {
        @Volatile
        private var lastError: String = ""

        override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
            val text = event.text
            CloudflaredOutput.findTunnelUrl(text)?.let { url ->
                setState(config, ConnectionState(ConnectionStatus.RUNNING, url))
                return
            }
            if (CloudflaredOutput.hasLoginPrompt(text)) {
                setState(config, ConnectionState(ConnectionStatus.STARTING, "Login required — see log"))
                return
            }
            if (outputType == ProcessOutputTypes.STDERR) {
                text.trim().takeIf { it.isNotEmpty() }?.let { lastError = it }
            }
        }

        override fun processTerminated(event: ProcessEvent) {
            handlers.remove(config.id)
            val state = if (event.exitCode == 0 || event.exitCode == TERMINATED_BY_USER) {
                ConnectionState(ConnectionStatus.STOPPED)
            } else {
                ConnectionState(ConnectionStatus.FAILED, lastError.ifBlank { "Exited with code ${event.exitCode}" })
            }
            setState(config, state)
        }
    }

    private fun setState(config: ConnectionConfig, newState: ConnectionState) {
        states[config.id] = newState
        fire { it.stateChanged(config, newState) }
    }

    // --- listeners -------------------------------------------------------------------------

    fun addListener(listener: Listener, parent: Disposable) {
        listeners.add(listener)
        com.intellij.openapi.util.Disposer.register(parent) { listeners.remove(listener) }
    }

    /** Process callbacks arrive off the EDT; everything downstream of this touches Swing. */
    private fun fire(action: (Listener) -> Unit) {
        SwingUtilities.invokeLater { listeners.forEach(action) }
    }

    override fun dispose() {
        stopAll()
    }

    companion object {
        const val DEFAULT_EXECUTABLE = "cloudflared"

        /** `destroyProcess()` on a SIGTERM'd child; not an error. */
        private const val TERMINATED_BY_USER = 143

        fun getInstance(project: Project): TunnelService = project.service()
    }
}
