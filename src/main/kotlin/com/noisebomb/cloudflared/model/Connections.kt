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

    /**
     * `cloudflared access <proto> --hostname <host> --url <bind>` — local listener in front of an
     * Access-protected service. The constant is still called `ACCESS_TCP` because it is written to
     * `cloudflaredRunner.xml` by name; renaming it would silently drop every saved connection.
     */
    ACCESS_TCP("Access client"), ;

    override fun toString(): String = displayName
}

/**
 * `cloudflared access` exposes `tcp`, `ssh`, `rdp` and `smb` as aliases of one command with
 * identical flags, so this only changes the word in the argument vector — and the one the user
 * reads back later to remember what the row is for.
 */
enum class AccessProtocol(val subcommand: String, val displayName: String) {
    TCP("tcp", "TCP"),
    SSH("ssh", "SSH"),
    RDP("rdp", "RDP"),
    SMB("smb", "SMB"), ;

    override fun toString(): String = displayName
}

/** `--protocol`. [AUTO] leaves the flag off and lets cloudflared negotiate. */
enum class TunnelProtocol(val flagValue: String, val displayName: String) {
    AUTO("", "Auto"),
    QUIC("quic", "QUIC"),
    HTTP2("http2", "HTTP/2"), ;

    override fun toString(): String = displayName
}

/** `--edge-ip-version`. */
enum class EdgeIpVersion(val flagValue: String, val displayName: String) {
    AUTO("", "Auto"),
    V4("4", "IPv4"),
    V6("6", "IPv6"), ;

    override fun toString(): String = displayName
}

/** `--loglevel` / `--log-level`. [DEFAULT] leaves the flag off, which cloudflared reads as `info`. */
enum class LogLevel(val flagValue: String, val displayName: String) {
    DEFAULT("", "Default"),
    DEBUG("debug", "Debug"),
    INFO("info", "Info"),
    WARN("warn", "Warn"),
    ERROR("error", "Error"),
    FATAL("fatal", "Fatal"), ;

    override fun toString(): String = displayName
}

/**
 * An optional tag colour for a row, in the spirit of Database tool window data sources — same names
 * and same order, so the two tool windows agree about what "Rose" is.
 *
 * Two pairs, because the swatch and the row background want different things. The accent is the
 * full-strength hue for the chip in the combo; the tint is the row background, given explicitly per
 * theme rather than derived, because mixing an accent into white desaturates towards grey and the
 * paler colours stop being tellable apart. RGB rather than `JBColor` so the model stays out of
 * Swing; [com.noisebomb.cloudflared.ui.ConnectionColors] turns these into paint.
 */
enum class ConnectionColor(
    val displayName: String,
    val lightRgb: Int,
    val darkRgb: Int,
    val tintLightRgb: Int,
    val tintDarkRgb: Int,
) {
    NONE("No color", 0, 0, 0, 0),
    BLUE("Blue", 0x389FD6, 0x3592C4, 0xDCEEFB, 0x2E4356),
    GREEN("Green", 0x59A869, 0x499C54, 0xDCEFDF, 0x2F4434),
    ORANGE("Orange", 0xE8A33D, 0xCC7832, 0xFBE7D2, 0x4A3A2A),
    ROSE("Rose", 0xE55765, 0xDB5C5C, 0xFBDDE1, 0x4B3033),
    VIOLET("Violet", 0x9A79D1, 0x8A64C7, 0xEADFF8, 0x3E3450),
    YELLOW("Yellow", 0xD9B143, 0xC2A03C, 0xF9EEC6, 0x453F2A), ;

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
    @Attribute var color: ConnectionColor = ConnectionColor.NONE,
    /** Path to a specific `cloudflared`. Blank means whatever the project setting resolves to. */
    @Attribute var executable: String = "",

    // --- access options ---
    @Attribute var accessProtocol: AccessProtocol = AccessProtocol.TCP,
    /** `--destination`: the machine behind the Access app to reach, for a jump-host setup. */
    @Attribute var destination: String = "",

    // --- quick tunnel options ---
    @Attribute var postQuantum: Boolean = false,
    @Attribute var tunnelProtocol: TunnelProtocol = TunnelProtocol.AUTO,
    @Attribute var edgeIpVersion: EdgeIpVersion = EdgeIpVersion.AUTO,
    /** `--region`: blank is the global region, which is what almost everyone wants. */
    @Attribute var region: String = "",
    @Attribute var httpHostHeader: String = "",
    @Attribute var originServerName: String = "",
    @Attribute var noTlsVerify: Boolean = false,
    @Attribute var noChunkedEncoding: Boolean = false,
    @Attribute var http2Origin: Boolean = false,
    /** `--socks5`: serve the local end as a SOCKS5 proxy instead of proxying one origin. */
    @Attribute var socks5: Boolean = false,
    /** `--no-autoupdate`: cloudflared otherwise restarts itself mid-session when a release lands. */
    @Attribute var noAutoUpdate: Boolean = false,
    /** `--retries`. [DEFAULT_RETRIES] is cloudflared's own default and stays off the command line. */
    @Attribute var retries: Int = DEFAULT_RETRIES,

    // --- shared ---
    @Attribute var logLevel: LogLevel = LogLevel.DEFAULT,
) {
    /** Argument vector handed to [com.intellij.execution.configurations.GeneralCommandLine]. */
    fun commandLine(executable: String): List<String> = buildList {
        add(executable)
        when (type) {
            ConnectionType.QUICK_TUNNEL -> {
                add("tunnel")
                if (postQuantum) add("--post-quantum")
                addFlag("--protocol", tunnelProtocol.flagValue)
                addFlag("--edge-ip-version", edgeIpVersion.flagValue)
                addFlag("--region", region)
                addFlag("--http-host-header", httpHostHeader)
                addFlag("--origin-server-name", originServerName)
                if (noTlsVerify) add("--no-tls-verify")
                if (noChunkedEncoding) add("--no-chunked-encoding")
                if (http2Origin) add("--http2-origin")
                if (socks5) add("--socks5")
                if (noAutoUpdate) add("--no-autoupdate")
                if (retries != DEFAULT_RETRIES) addFlag("--retries", retries.toString())
                addFlag("--loglevel", logLevel.flagValue)
                // Last, so it reads like the command the user would have typed.
                add("--url")
                add(target)
            }

            ConnectionType.ACCESS_TCP -> {
                add("access")
                add(accessProtocol.subcommand)
                add("--hostname")
                add(target)
                add("--url")
                add(localBind)
                addFlag("--destination", destination)
                addFlag("--log-level", logLevel.flagValue)
            }
        }
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

    /** [fallback] is the project-wide executable, itself defaulting to the one on PATH. */
    fun resolveExecutable(fallback: String): String = executable.ifBlank { fallback }

    /**
     * Whether [target] is something a quick tunnel will serve over its public HTTPS hostname. A
     * scheme cloudflared understands but the public URL cannot carry — `tcp://`, `ssh://`, `unix:`
     * — is a deliberate choice by the user, not a mistake worth warning about.
     */
    fun isHttpTarget(): Boolean {
        val scheme = target.substringBefore("://", "").lowercase()
        return scheme.isEmpty() || scheme == "http" || scheme == "https"
    }

    private fun MutableList<String>.addFlag(flag: String, value: String) {
        if (value.isBlank()) return
        add(flag)
        add(value.trim())
    }

    companion object {
        /** cloudflared's own `--retries` default; anything else is worth putting on the command line. */
        const val DEFAULT_RETRIES = 5

        /**
         * What to put in an empty Name field once the user has typed a target: the host, without
         * the scheme or path noise. Blank when there is nothing to go on.
         */
        fun suggestName(type: ConnectionType, target: String): String {
            val hostPort = target.substringAfter("://", target).trim().trimEnd('/').substringBefore('/')
            return when (type) {
                ConnectionType.QUICK_TUNNEL -> hostPort
                // A port on an Access hostname would be wrong anyway, and reads badly as a label.
                ConnectionType.ACCESS_TCP -> hostPort.substringBefore(':')
            }
        }
    }
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
    /**
     * Something wrong that the connection nevertheless survives — a quick tunnel whose local
     * service is down still serves, it just answers 502. Cleared as soon as the cause goes away.
     */
    val warning: String = "",
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
