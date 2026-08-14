package com.noisebomb.cloudflared.model

import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.Tag

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

    fun copyOf(): ConnectionConfig = copy()
}

enum class ConnectionStatus { STOPPED, STARTING, RUNNING, FAILED }

/**
 * Live state for one connection. [detail] is whatever the user actually needs to see: the generated
 * trycloudflare URL, the local bind address, an auth hint, or an error.
 */
data class ConnectionState(
    val status: ConnectionStatus = ConnectionStatus.STOPPED,
    val detail: String = "",
)
