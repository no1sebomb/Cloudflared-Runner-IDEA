package com.noisebomb.cloudflared.model

import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.Tag
import java.util.Locale

/**
 * The two things this plugin can spawn. Both are plain child processes; they only differ in the
 * argument vector and in what counts as useful status.
 */
enum class ConnectionType(val displayName: String) {
    /** `cloudflared tunnel --url <target>` — anonymous ephemeral tunnel, prints a trycloudflare.com host. */
    QUICK_TUNNEL("Quick tunnel"),

    /** `cloudflared access tcp --hostname <host> --url <bind>` — local listener in front of an Access-protected service. */
    ACCESS_TCP("Access client");

    override fun toString(): String = displayName
}

@Tag("connection")
data class ConnectionConfig(
    @Attribute var id: String = "",
    @Attribute var name: String = "",
    @Attribute var type: ConnectionType = ConnectionType.QUICK_TUNNEL,
    /** Quick tunnel: the local service (`localhost:8080`, `tcp://localhost:5432`). Access: the Access hostname. */
    @Attribute var target: String = "",
    /** Access only: the local address cloudflared should listen on (`localhost:5433`). */
    @Attribute var localBind: String = "",
) {
    /** Argument vector handed to [com.intellij.execution.configurations.GeneralCommandLine]. */
    fun commandLine(executable: String): List<String> = when (type) {
        ConnectionType.QUICK_TUNNEL -> listOf(executable, "tunnel", "--url", target)
        ConnectionType.ACCESS_TCP -> listOf(executable, "access", "tcp", "--hostname", target, "--url", localBind)
    }

    fun displayName(): String = name.ifBlank {
        when (type) {
            ConnectionType.QUICK_TUNNEL -> target
            ConnectionType.ACCESS_TCP -> "$target → $localBind"
        }
    }

    /**
     * The address on this machine. For a quick tunnel that is the service being exposed; for an
     * access client it is the listener cloudflared opens.
     */
    fun localAddress(): String = when (type) {
        ConnectionType.QUICK_TUNNEL -> target
        ConnectionType.ACCESS_TCP -> localBind
    }

    fun copyOf(): ConnectionConfig = copy()
}

enum class ConnectionStatus(val label: String) {
    STOPPED("Stopped"),
    STARTING("Starting"),
    RUNNING("Running"),

    /** cloudflared is waiting for the user to finish the Access SSO round trip. */
    AWAITING_AUTH("Authorization required"),
    FAILED("Failed"),
}

/**
 * Live state for one connection. [detail] is whatever the user actually needs to see: the generated
 * trycloudflare URL, the local bind address, an auth hint, or a short error summary.
 */
data class ConnectionState(
    val status: ConnectionStatus = ConnectionStatus.STOPPED,
    val detail: String = "",
    /** Only a quick tunnel gets one, and only once cloudflared has printed it. */
    val publicUrl: String = "",
    /** The SSO link cloudflared printed while waiting for authorization, if it printed one. */
    val authUrl: String = "",
    /** Epoch millis the current [ConnectionStatus.RUNNING] stretch began; 0 when not running. */
    val runningSince: Long = 0L,
) {
    /** `MM:SS`, or `H:MM:SS` past the hour. Empty unless running. */
    fun uptime(now: Long = System.currentTimeMillis()): String {
        if (status != ConnectionStatus.RUNNING || runningSince <= 0L) return ""
        val seconds = ((now - runningSince) / 1000).coerceAtLeast(0)
        val hours = seconds / 3600
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, (seconds % 3600) / 60, seconds % 60)
        } else {
            String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
        }
    }
}
