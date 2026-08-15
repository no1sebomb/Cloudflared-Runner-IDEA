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
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.util.xmlb.annotations.XCollection
import com.noisebomb.cloudflared.model.ConnectionConfig
import com.noisebomb.cloudflared.model.ConnectionState
import com.noisebomb.cloudflared.model.ConnectionStatus
import com.noisebomb.cloudflared.model.ConnectionType
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
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

        /** Whether the log section under the table is unfolded. */
        @JvmField
        var logExpanded: Boolean = true
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

    /** Ids whose process we killed on purpose, so its non-zero exit is not reported as a failure. */
    private val stopRequested = ConcurrentHashMap.newKeySet<String>()
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

    var logExpanded: Boolean
        get() = state.logExpanded
        set(value) {
            state.logExpanded = value
        }

    fun indexOf(config: ConnectionConfig): Int = state.connections.indexOfFirst { it.id == config.id }

    /** [index] of -1 appends. */
    fun addConnection(config: ConnectionConfig, index: Int = -1): ConnectionConfig {
        if (config.id.isBlank()) config.id = UUID.randomUUID().toString()
        if (index in 0..state.connections.size) state.connections.add(index, config) else state.connections.add(config)
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

    fun moveConnection(from: Int, to: Int) {
        val indices = state.connections.indices
        if (from == to || from !in indices || to !in indices) return
        state.connections.add(to, state.connections.removeAt(from))
        fire { it.connectionsChanged() }
    }

    // --- process control -------------------------------------------------------------------

    fun stateOf(config: ConnectionConfig): ConnectionState = states[config.id] ?: ConnectionState()

    fun isRunning(config: ConnectionConfig): Boolean = handlers[config.id]?.isProcessTerminated == false

    fun start(config: ConnectionConfig) {
        if (isRunning(config)) return
        stopRequested.remove(config.id)

        val commandLine = GeneralCommandLine(config.commandLine(executable))
            .withCharset(StandardCharsets.UTF_8)
            .withWorkDirectory(project.basePath)

        val handler = try {
            KillableColoredProcessHandler(commandLine)
        } catch (e: ExecutionException) {
            thisLogger().info("Failed to start cloudflared", e)
            val summary = CloudflaredOutput.summarize(e.message.orEmpty(), NOT_STARTED)
            setState(config, ConnectionState(ConnectionStatus.FAILED, summary))
            return
        }

        handlers[config.id] = handler
        handler.addProcessListener(StatusListener(config))
        fire { it.processCreated(config, handler) }

        // Neither type is usable the instant the process starts: a quick tunnel has to be granted a
        // hostname, an access client has to open its listener (and possibly authenticate first).
        setState(
            config,
            when (config.type) {
                ConnectionType.QUICK_TUNNEL -> ConnectionState(ConnectionStatus.STARTING, "Requesting tunnel…")
                ConnectionType.ACCESS_TCP -> ConnectionState(ConnectionStatus.STARTING)
            },
        )
        handler.startNotify()
        if (config.type == ConnectionType.ACCESS_TCP) {
            scheduleAccessReadyFallback(config)
            probeAccessListener(config)
        }
    }

    fun stop(config: ConnectionConfig) {
        val handler = handlers[config.id] ?: return
        stopRequested.add(config.id)
        handler.destroyProcess()
    }

    /** Stop-then-start. The restart has to wait for the old process to actually die. */
    fun restart(config: ConnectionConfig) {
        val handler = handlers[config.id]
        if (handler == null || handler.isProcessTerminated) {
            start(config)
            return
        }
        handler.addProcessListener(object : ProcessListener {
            override fun processTerminated(event: ProcessEvent) {
                SwingUtilities.invokeLater { start(config) }
            }
        })
        stopRequested.add(config.id)
        handler.destroyProcess()
    }

    fun stopAll() {
        stopRequested.addAll(handlers.keys)
        handlers.values.forEach { it.destroyProcess() }
    }

    /**
     * Not every cloudflared build announces its access listener in a way we recognise, and a status
     * stuck on "Starting" forever would be worse than being slightly optimistic. If the process is
     * still alive after the grace period and nothing asked for authentication, call it running.
     */
    private fun scheduleAccessReadyFallback(config: ConnectionConfig) {
        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            if (states[config.id]?.status == ConnectionStatus.STARTING && isRunning(config)) {
                markRunning(config)
            }
        }, ACCESS_READY_GRACE_SECONDS, TimeUnit.SECONDS)
    }

    /**
     * `cloudflared access tcp` does not talk to Cloudflare until something connects to its local
     * listener, so an expired token stays invisible — the connection looks fine and only fails when
     * the user finally points a client at it. Opening and immediately closing one socket forces that
     * round trip, which makes cloudflared print its SSO link at start-up instead.
     */
    private fun probeAccessListener(config: ConnectionConfig) {
        val port = config.localBind.substringAfterLast(':', "").toIntOrNull() ?: return
        val host = config.localBind.substringBeforeLast(':', "").ifBlank { "localhost" }
        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            if (!isRunning(config)) return@schedule
            try {
                Socket().use { it.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MILLIS) }
            } catch (e: IOException) {
                // The listener may not be up yet, or may be gone again. Neither is worth reporting:
                // the probe exists to nudge cloudflared, not to decide whether it is healthy.
                thisLogger().debug("Access listener probe failed", e)
            }
        }, PROBE_DELAY_MILLIS, TimeUnit.MILLISECONDS)
    }

    /** Streams output looking for the few lines that carry status. Called off the EDT. */
    private inner class StatusListener(private val config: ConnectionConfig) : ProcessListener {
        @Volatile
        private var lastError: String = ""

        override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
            val text = event.text
            when (config.type) {
                ConnectionType.QUICK_TUNNEL -> CloudflaredOutput.findTunnelUrl(text)?.let { url ->
                    markRunning(config, publicUrl = url)
                    return
                }

                ConnectionType.ACCESS_TCP -> {
                    // Order matters: the login prompt is printed before the listener line, and only
                    // the prompt is worth surfacing while it is pending.
                    CloudflaredOutput.findLoginUrl(text)?.let { url ->
                        setState(config, ConnectionState(ConnectionStatus.AWAITING_AUTH, authUrl = url))
                        return
                    }
                    if (CloudflaredOutput.hasLoginPrompt(text)) {
                        setState(
                            config,
                            ConnectionState(ConnectionStatus.AWAITING_AUTH, "Open the link in the log"),
                        )
                        return
                    }
                    if (CloudflaredOutput.hasAccessListener(text)) {
                        markRunning(config)
                        return
                    }
                }
            }
            if (outputType == ProcessOutputTypes.STDERR) {
                text.trim().takeIf { it.isNotEmpty() }?.let { lastError = it }
            }
        }

        override fun processTerminated(event: ProcessEvent) {
            handlers.remove(config.id)
            val stopped = stopRequested.remove(config.id) || event.exitCode in SIGNAL_EXIT_CODES
            val state = if (event.exitCode == 0 || stopped) {
                ConnectionState(ConnectionStatus.STOPPED)
            } else {
                ConnectionState(ConnectionStatus.FAILED, CloudflaredOutput.summarize(lastError, event.exitCode))
            }
            setState(config, state)
        }
    }

    /** Keeps the uptime clock running across repeated "still running" signals. */
    private fun markRunning(config: ConnectionConfig, publicUrl: String = "") {
        val previous = states[config.id]
        val since = previous
            ?.takeIf { it.status == ConnectionStatus.RUNNING && it.runningSince > 0L }
            ?.runningSince
            ?: System.currentTimeMillis()
        setState(config, ConnectionState(ConnectionStatus.RUNNING, publicUrl = publicUrl, runningSince = since))
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

        /**
         * `128 + signal`. A killable handler soft-kills with SIGINT (130) before escalating to
         * SIGTERM (143) or SIGKILL (137), and none of the three mean cloudflared went wrong.
         */
        private val SIGNAL_EXIT_CODES = setOf(130, 137, 143)

        private const val ACCESS_READY_GRACE_SECONDS = 5L

        /** Long enough for the listener to bind, short enough that the user sees it as "on start". */
        private const val PROBE_DELAY_MILLIS = 1500L
        private const val PROBE_TIMEOUT_MILLIS = 2000

        /** No exit code exists when the process never got off the ground. */
        private const val NOT_STARTED = -1

        fun getInstance(project: Project): TunnelService = project.service()
    }
}
