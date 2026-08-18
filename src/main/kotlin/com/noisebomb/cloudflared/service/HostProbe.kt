package com.noisebomb.cloudflared.service

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.util.io.HttpRequests
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.UnknownHostException

/**
 * "Is anything listening there?", shared by the status watchdog and the add/edit dialog. Deliberately
 * dumb: one connect and drop, no protocol handshake, no retries.
 */
object HostProbe {

    const val HTTPS_PORT = 443
    private const val HTTP_PORT = 80
    private const val MAX_PORT = 65535
    private const val DEFAULT_TIMEOUT_MILLIS = 1000
    private const val HTTP_TIMEOUT_MILLIS = 4000

    /**
     * `tcp://localhost:5432`, `http://localhost:3000` and bare `localhost:8080` all turn up here.
     * [fallbackPort] covers addresses that legitimately carry no port, such as an Access hostname.
     */
    fun socketAddress(address: String, fallbackPort: Int? = null): InetSocketAddress? {
        val hostPort = address.substringAfter("://", address).trim().trimEnd('/').substringBefore('/')
        val host = hostPort.substringBeforeLast(':', "").ifBlank { hostPort }
        val port = hostPort.substringAfterLast(':', "").toIntOrNull()
            ?: schemePort(address)
            ?: fallbackPort
            ?: return null
        return if (host.isBlank() || port !in 1..MAX_PORT) null else InetSocketAddress(host, port)
    }

    /** One connect-and-drop. Failure only ever means "not right now" to the caller. */
    fun canConnect(
        address: String,
        fallbackPort: Int? = null,
        timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    ): Boolean {
        val target = socketAddress(address, fallbackPort) ?: return false
        return try {
            Socket().use { it.connect(target, timeoutMillis) }
            true
        } catch (e: IOException) {
            thisLogger().debug("Probe of $address failed", e)
            false
        }
    }

    /**
     * Whether this machine would let cloudflared open a listener there. Binding and immediately
     * closing is the only honest answer: the port could still be taken a second later, but the
     * common case — something is already sitting on it — is caught before the process is spawned.
     */
    fun isPortAvailable(address: String): Boolean {
        val target = socketAddress(address) ?: return true
        return try {
            ServerSocket().use {
                it.reuseAddress = false
                it.bind(target)
            }
            true
        } catch (e: IOException) {
            thisLogger().debug("Bind test of $address failed", e)
            false
        }
    }

    /**
     * The `Server` response header, lower-cased, or null if the host did not answer HTTPS at all.
     * A HEAD is enough and redirects are left unfollowed on purpose — an Access-protected hostname
     * answers with a 302 to the SSO page, and that response is already Cloudflare's.
     */
    fun serverHeader(host: String): String? = try {
        HttpRequests.head("https://$host/")
            .connectTimeout(HTTP_TIMEOUT_MILLIS)
            .readTimeout(HTTP_TIMEOUT_MILLIS)
            .followRedirects(false)
            .throwStatusCodeException(false)
            .connect { it.connection.getHeaderField("Server")?.lowercase() }
    } catch (e: IOException) {
        thisLogger().debug("HEAD of $host failed", e)
        null
    }

    /**
     * Whether the thing on this address answers HTTP. A quick tunnel's public hostname only ever
     * serves HTTP(S), so an origin that speaks some other protocol produces a tunnel that comes up
     * and then fails every request.
     *
     * Only meaningful for an address the caller already knows should be HTTP — see
     * [com.noisebomb.cloudflared.model.ConnectionConfig.isHttpTarget], which keeps `tcp://` and
     * friends away from this.
     */
    fun speaksHttp(address: String): Boolean {
        val target = socketAddress(address) ?: return true
        val scheme = if (address.startsWith("https://")) "https" else "http"
        return try {
            HttpRequests.head("$scheme://${target.hostString}:${target.port}/")
                .connectTimeout(HTTP_TIMEOUT_MILLIS)
                .readTimeout(HTTP_TIMEOUT_MILLIS)
                .followRedirects(false)
                .throwStatusCodeException(false)
                .tryConnect()
            true
        } catch (e: IOException) {
            thisLogger().debug("HTTP probe of $address failed", e)
            false
        }
    }

    /** DNS only. Separates "that name does not exist" from "nothing answered", which read differently. */
    fun resolves(host: String): Boolean = try {
        InetAddress.getByName(host)
        true
    } catch (e: UnknownHostException) {
        thisLogger().debug("Lookup of $host failed", e)
        false
    }

    private fun schemePort(address: String): Int? = when {
        address.startsWith("https://") -> HTTPS_PORT
        address.startsWith("http://") -> HTTP_PORT
        else -> null
    }
}
