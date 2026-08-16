package com.noisebomb.cloudflared.service

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.IdeFrame
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.io.BaseOutputReader
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

    /** Ids whose access listener is still being probed; see [probeAccessListener]. */
    private val probePending = ConcurrentHashMap.newKeySet<String>()

    /** When cloudflared last asked for authorization, per id. See [recheckAuthorization]. */
    private val authPromptAt = ConcurrentHashMap<String, Long>()

    /** Window activation fires in bursts; one re-check per burst is plenty. */
    @Volatile
    private var lastAuthRecheck = 0L

    /** Same, for the health check that runs while a connection is up. */
    private val healthWatched = ConcurrentHashMap.newKeySet<String>()

    /** Ids we killed over an error worth keeping on screen; see [failAndStop]. */
    private val fatalError = ConcurrentHashMap.newKeySet<String>()
    private val listeners = CopyOnWriteArrayList<Listener>()

    init {
        // Coming back to the IDE is the one reliable sign that a browser login just finished.
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(
                ApplicationActivationListener.TOPIC,
                object : ApplicationActivationListener {
                    override fun applicationActivated(ideFrame: IdeFrame) = recheckAuthorization()
                },
            )
    }

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
            CloudflaredProcessHandler(commandLine)
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
        if (config.type == ConnectionType.ACCESS_TCP) probeAccessListener(config)
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
     * `cloudflared access tcp` does not talk to Cloudflare until something connects to its local
     * listener, so an expired token stays invisible — the connection looks fine and only fails when
     * the user finally points a client at it. Opening and immediately closing one socket forces that
     * round trip, which makes cloudflared print its SSO link at start-up instead.
     *
     * The connection is retried in short steps rather than waited out with one long delay, so the
     * row leaves "Starting" as soon as the listener is actually up.
     */
    private fun probeAccessListener(config: ConnectionConfig) {
        if (socketAddress(config.localBind) == null) {
            // Nothing to connect to. Do not leave the row stuck on "Starting" over it.
            schedule(PROBE_SETTLE_MILLIS) { promoteIfStarting(config) }
            return
        }
        probePending.add(config.id)
        probeAttempt(config, attempt = 0)
    }

    private fun probeAttempt(config: ConnectionConfig, attempt: Int) {
        schedule(if (attempt == 0) PROBE_FIRST_DELAY_MILLIS else PROBE_RETRY_MILLIS) {
            if (!isRunning(config)) {
                probePending.remove(config.id)
                return@schedule
            }
            if (!connectToListener(config) && attempt + 1 < PROBE_MAX_ATTEMPTS) {
                probeAttempt(config, attempt + 1)
                return@schedule
            }
            // cloudflared answers the probe before it prints anything about authorization, so give
            // the prompt a moment to win the race before calling the connection ready.
            schedule(PROBE_SETTLE_MILLIS) {
                probePending.remove(config.id)
                promoteIfStarting(config)
            }
        }
    }

    /** Leaves an authorization prompt — or a failure — alone; only lifts the "Starting" placeholder. */
    private fun promoteIfStarting(config: ConnectionConfig) {
        if (states[config.id]?.status == ConnectionStatus.STARTING && isRunning(config)) markRunning(config)
    }

    private fun connectToListener(config: ConnectionConfig): Boolean = canConnect(config.localBind)

    /** One connect-and-drop. Failure only ever means "not yet" to the caller. */
    private fun canConnect(address: String): Boolean {
        val target = socketAddress(address) ?: return false
        return try {
            Socket().use { it.connect(target, PROBE_TIMEOUT_MILLIS) }
            true
        } catch (e: IOException) {
            thisLogger().debug("Probe of $address failed", e)
            false
        }
    }

    /** `tcp://localhost:5432`, `http://localhost:3000` and bare `localhost:8080` all turn up here. */
    private fun socketAddress(address: String): InetSocketAddress? {
        val hostPort = address.substringAfter("://", address).trim().trimEnd('/').substringBefore('/')
        val host = hostPort.substringBeforeLast(':', "").ifBlank { hostPort }
        val port = hostPort.substringAfterLast(':', "").toIntOrNull()
            ?: defaultPort(address)
            ?: return null
        return if (host.isBlank() || port !in 1..MAX_PORT) null else InetSocketAddress(host, port)
    }

    private fun defaultPort(address: String): Int? = when {
        address.startsWith("https://") -> HTTPS_PORT
        address.startsWith("http://") -> HTTP_PORT
        else -> null
    }

    /**
     * cloudflared says nothing when a browser login finally succeeds, so the only way to notice is
     * to connect again: a connection that goes through *without* producing a fresh authorization
     * prompt means the token is good now.
     *
     * This cannot be polled. Every connection to an unauthorized client starts another login, and
     * cloudflared answers the second one with "another cloudflared process is already waiting for
     * authentication" and reprints the URL — so a poll produces a browser tab and a wall of log per
     * round, and never gets a quiet answer to read. Instead it runs once when the IDE window comes
     * back to the front, which is exactly what happens after a login finishes in the browser.
     */
    private fun recheckAuthorization() {
        val now = System.currentTimeMillis()
        if (now - lastAuthRecheck < AUTH_RECHECK_THROTTLE_MILLIS) return
        lastAuthRecheck = now
        connections.filter { awaitingAuth(it) }.forEach { config ->
            val promptedBefore = authPromptAt[config.id]
            schedule(0) {
                connectToListener(config)
                // A rejected connection reprints the prompt; give it time to arrive before reading.
                schedule(AUTH_SETTLE_MILLIS) {
                    if (awaitingAuth(config) && authPromptAt[config.id] == promptedBefore) markRunning(config)
                }
            }
        }
    }

    /**
     * A connection can stop working without the process noticing: for a quick tunnel the service it
     * fronts can go away, and either kind can be killed from outside in a way the process handler
     * misses. Nothing in the output says so, so poll.
     *
     * Only a quick tunnel is probed over a socket. An access client's listener is the process
     * itself, so checking it is alive says everything a connection would — and every connection to
     * an access client is a real Access round trip that is not worth making twice a second.
     *
     * An unreachable local service is a warning, never a failure: the tunnel is up and Cloudflare
     * answers on it, with a 502 until the service is back. The loop keeps running while warned, so
     * the warning clears itself the moment the service returns.
     */
    private fun scheduleHealthCheck(config: ConnectionConfig, failures: Int) {
        schedule(HEALTH_INTERVAL_MILLIS) {
            if (states[config.id]?.status != ConnectionStatus.RUNNING) {
                healthWatched.remove(config.id)
                return@schedule
            }
            if (!isRunning(config)) {
                healthWatched.remove(config.id)
                setState(config, ConnectionState(ConnectionStatus.STOPPED))
                return@schedule
            }
            val checkable = config.type == ConnectionType.QUICK_TUNNEL && socketAddress(config.target) != null
            if (!checkable || canConnect(config.target)) {
                setWarning(config, "")
                scheduleHealthCheck(config, failures = 0)
                return@schedule
            }
            // One refusal is the local service being restarted; two in a row is it being gone.
            val seen = (failures + 1).coerceAtMost(HEALTH_FAILURES_ALLOWED)
            if (seen >= HEALTH_FAILURES_ALLOWED) setWarning(config, ORIGIN_UNREACHABLE)
            scheduleHealthCheck(config, seen)
        }
    }

    /** Warnings ride along with RUNNING rather than replacing it. */
    private fun setWarning(config: ConnectionConfig, warning: String) {
        val current = states[config.id] ?: return
        if (current.status != ConnectionStatus.RUNNING || current.warning == warning) return
        setState(config, current.copy(warning = warning))
    }

    private fun awaitingAuth(config: ConnectionConfig): Boolean =
        states[config.id]?.status == ConnectionStatus.AWAITING_AUTH && isRunning(config)

    /** The timestamp is what [recheckAuthorization] compares against to spot a quiet round trip. */
    private fun setAwaitingAuth(config: ConnectionConfig, state: ConnectionState) {
        authPromptAt[config.id] = System.currentTimeMillis()
        setState(config, state)
    }

    /**
     * A failure the process itself shrugs off. cloudflared keeps its listener open after an origin
     * it can never reach, which leaves a connection that looks alive and serves nothing, so take it
     * down — and remember why, or [StatusListener] would overwrite the reason with "Stopped".
     */
    private fun failAndStop(config: ConnectionConfig, summary: String) {
        setState(config, ConnectionState(ConnectionStatus.FAILED, summary))
        fatalError.add(config.id)
        handlers[config.id]?.destroyProcess()
    }

    private fun schedule(delayMillis: Long, action: () -> Unit) {
        AppExecutorUtil.getAppScheduledExecutorService().schedule(action, delayMillis, TimeUnit.MILLISECONDS)
    }

    /**
     * A tunnel says its piece at start-up and then goes quiet for hours. Left on the default reader
     * options the platform polls it constantly and eventually logs a warning about it; the silent
     * options back off to a blocking read instead, which costs nothing while nothing is happening.
     */
    private class CloudflaredProcessHandler(commandLine: GeneralCommandLine) :
        KillableColoredProcessHandler(commandLine) {
        override fun readerOptions(): BaseOutputReader.Options = BaseOutputReader.Options.forMostlySilentProcess()
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
                    if (CloudflaredOutput.hasOriginFailure(text)) {
                        failAndStop(config, CloudflaredOutput.summarize(text, NOT_STARTED))
                        return
                    }
                    // Order matters: the login prompt is printed before the listener line, and only
                    // the prompt is worth surfacing while it is pending.
                    CloudflaredOutput.findLoginUrl(text)?.let { url ->
                        setAwaitingAuth(config, ConnectionState(ConnectionStatus.AWAITING_AUTH, authUrl = url))
                        return
                    }
                    if (CloudflaredOutput.hasLoginPrompt(text)) {
                        setAwaitingAuth(
                            config,
                            ConnectionState(ConnectionStatus.AWAITING_AUTH, "Open the link in the log"),
                        )
                        return
                    }
                    // While the probe is in flight this line means nothing — the listener binds long
                    // before anyone knows whether the token is still good. Once the probe is done it
                    // is the one signal that a completed browser login has put the client to work.
                    if (config.id !in probePending && CloudflaredOutput.hasAccessListener(text)) {
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
            probePending.remove(config.id)
            authPromptAt.remove(config.id)
            healthWatched.remove(config.id)
            val stopped = stopRequested.remove(config.id) || event.exitCode in SIGNAL_EXIT_CODES
            // The reason is already on screen and is more use than "Stopped".
            if (fatalError.remove(config.id)) return
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
        if (healthWatched.add(config.id)) scheduleHealthCheck(config, failures = 0)
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

        /** Short enough that the user reads it as "on start", long enough to usually hit first try. */
        private const val PROBE_FIRST_DELAY_MILLIS = 100L
        private const val PROBE_RETRY_MILLIS = 500L

        /** 100ms + 19 × 500ms ≈ 10s before giving up and trusting the process. */
        private const val PROBE_MAX_ATTEMPTS = 20
        private const val PROBE_TIMEOUT_MILLIS = 1000

        /**
         * Grace for the authorization prompt to arrive after the listener answers. cloudflared
         * accepts the connection first and only then discovers it has no usable token, so promoting
         * too eagerly shows "Running" for a moment before the row corrects itself.
         */
        private const val PROBE_SETTLE_MILLIS = 2000L

        private const val AUTH_SETTLE_MILLIS = 1000L

        /** Activation can fire several times over a single alt-tab. */
        private const val AUTH_RECHECK_THROTTLE_MILLIS = 3000L

        private const val HEALTH_INTERVAL_MILLIS = 2000L
        private const val HEALTH_FAILURES_ALLOWED = 2
        private const val ORIGIN_UNREACHABLE = "local service is down, public URL returns 502"

        private const val MAX_PORT = 65535
        private const val HTTP_PORT = 80
        private const val HTTPS_PORT = 443

        /** No exit code exists when the process never got off the ground. */
        private const val NOT_STARTED = -1

        fun getInstance(project: Project): TunnelService = project.service()
    }
}
