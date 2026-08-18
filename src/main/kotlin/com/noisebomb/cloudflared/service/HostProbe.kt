package com.noisebomb.cloudflared.service

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.util.io.HttpRequests
import java.io.BufferedReader
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

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
    private const val SERVER_HEADER = "server"

    /** Enough for any real response head; a bound so a hostile peer cannot stream headers forever. */
    private const val MAX_HEADERS = 100

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

    /**
     * One connect-and-drop. Failure only ever means "not right now" to the caller.
     *
     * Every address the name resolves to is tried, not just the first. `localhost` is two addresses
     * on a dual-stack machine — `::1` and `127.0.0.1` — and a server bound to one refuses the other,
     * so probing a single address reports "nothing is listening" for a service that is plainly up.
     * Anything that would actually reach it (curl, cloudflared, `Socket(host, port)`) walks the whole
     * list; [Socket.connect] with an already-resolved address is the one thing that does not.
     */
    fun canConnect(
        address: String,
        fallbackPort: Int? = null,
        timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    ): Boolean {
        val target = socketAddress(address, fallbackPort) ?: return false
        return candidates(target).any { candidate ->
            try {
                Socket().use { it.connect(InetSocketAddress(candidate, target.port), timeoutMillis) }
                true
            } catch (e: IOException) {
                thisLogger().debug("Probe of $candidate:${target.port} failed", e)
                false
            }
        }
    }

    /**
     * Whether this machine would let cloudflared open a listener there. Binding and immediately
     * closing is the only honest answer: the port could still be taken a second later, but the
     * common case — something is already sitting on it — is caught before the process is spawned.
     */
    fun isPortAvailable(address: String): Boolean {
        val target = socketAddress(address) ?: return true
        // Free on every address the name covers, for the same dual-stack reason as [canConnect]:
        // cloudflared will take whichever of them it can, so one taken address is a taken port.
        return candidates(target).all { candidate ->
            try {
                ServerSocket().use {
                    it.reuseAddress = false
                    it.bind(InetSocketAddress(candidate, target.port))
                }
                true
            } catch (e: IOException) {
                thisLogger().debug("Bind test of $candidate:${target.port} failed", e)
                false
            }
        }
    }

    /**
     * The `Server` response header, lower-cased, or null if the host did not answer HTTPS at all.
     * A HEAD is enough and redirects are left unfollowed on purpose — an Access-protected hostname
     * answers with a 302 to the SSO page, and that response is already Cloudflare's.
     *
     * Every address the name carries is tried, for the same reason as [canConnect] but a different
     * cause: a Cloudflare hostname has both A and AAAA records, and on a network whose IPv6 is
     * advertised but broken the AAAA is picked and nothing comes back.
     */
    fun serverHeader(host: String): String? {
        val target = socketAddress(host, HTTPS_PORT) ?: return null
        for (candidate in candidates(target)) {
            val response = exchange(candidate, target, tls = true)
            if (response is Response.Answered) return response.headers[SERVER_HEADER]
        }
        // Nothing answered on any address. Being behind a proxy is the reason worth handling: this
        // host is out on the internet, unlike everything else here, and a raw socket knows nothing
        // about the IDE's proxy settings while HttpRequests does.
        return proxiedServerHeader(host)
    }

    private fun proxiedServerHeader(host: String): String? = try {
        HttpRequests.head("https://$host/")
            .connectTimeout(HTTP_TIMEOUT_MILLIS)
            .readTimeout(HTTP_TIMEOUT_MILLIS)
            .followRedirects(false)
            .throwStatusCodeException(false)
            .connect { it.connection.getHeaderField("Server")?.lowercase() }
    } catch (e: IOException) {
        thisLogger().debug("Proxied HEAD of $host failed", e)
        null
    }

    /**
     * Whether the thing on this address answers HTTP. A quick tunnel's public hostname only ever
     * serves HTTP(S), so an origin that speaks some other protocol produces a tunnel that comes up
     * and then fails every request.
     *
     * Ambiguity resolves to `true`: the caller turns `false` into a warning aimed at the user, so
     * anything short of a clear "that is not HTTP" is better left unsaid.
     *
     * Only meaningful for an address the caller already knows should be HTTP — see
     * [com.noisebomb.cloudflared.model.ConnectionConfig.isHttpTarget], which keeps `tcp://` and
     * friends away from this.
     */
    fun speaksHttp(address: String): Boolean {
        val target = socketAddress(address) ?: return true
        val tls = address.startsWith("https://")
        return candidates(target).any { candidate ->
            when (val response = exchange(candidate, target, tls)) {
                is Response.Answered -> response.status.startsWith("HTTP/")
                Response.Inconclusive -> true
                Response.Failed -> false
            }
        }
    }

    private sealed interface Response {
        data class Answered(val status: String, val headers: Map<String, String>) : Response

        /** Something is speaking TLS, we just could not finish with it. */
        data object Inconclusive : Response

        data object Failed : Response
    }

    /**
     * One HEAD over a socket we opened ourselves, rather than through [HttpRequests].
     *
     * `HttpURLConnection`, which [HttpRequests] wraps, resolves the host to a single address and
     * connects to only that one — the same dual-stack trap [canConnect] documents. A dev server
     * bound to one of `::1` and `127.0.0.1` therefore reads as "answered, but not over HTTP" when
     * the resolver happens to return the other first.
     */
    private fun exchange(candidate: InetAddress, target: InetSocketAddress, tls: Boolean): Response {
        val host = target.hostString
        return try {
            Socket().use { plain ->
                plain.connect(InetSocketAddress(candidate, target.port), DEFAULT_TIMEOUT_MILLIS)
                plain.soTimeout = HTTP_TIMEOUT_MILLIS
                val socket = if (tls) handshake(plain, host, target.port) else plain
                socket.getOutputStream().apply {
                    write(headRequest(host, target.port))
                    flush()
                }
                // ISO-8859-1 is what a response head is defined in, and it cannot fail to decode.
                val reader = socket.getInputStream().bufferedReader(StandardCharsets.ISO_8859_1)
                val status = reader.readLine().orEmpty()
                if (status.isEmpty()) Response.Failed else Response.Answered(status, readHeaders(reader))
            }
        } catch (e: SSLException) {
            // A self-signed development certificate being the usual reason.
            thisLogger().debug("TLS probe of $candidate:${target.port} was inconclusive", e)
            Response.Inconclusive
        } catch (e: IOException) {
            thisLogger().debug("HTTP probe of $candidate:${target.port} failed", e)
            Response.Failed
        }
    }

    /**
     * Up to the blank line that ends the head. Names are lower-cased; HTTP treats them as such.
     *
     * Internal rather than private so the parsing can be tested without standing up a TLS server.
     */
    internal fun readHeaders(reader: BufferedReader): Map<String, String> = buildMap {
        repeat(MAX_HEADERS) {
            val line = reader.readLine()
            if (line.isNullOrBlank()) return@buildMap
            val name = line.substringBefore(':', "").trim().lowercase()
            if (name.isNotEmpty()) put(name, line.substringAfter(':').trim().lowercase())
        }
    }

    private fun handshake(socket: Socket, host: String, port: Int): Socket =
        (SSLSocketFactory.getDefault() as SSLSocketFactory)
            .createSocket(socket, host, port, false)
            .also { (it as SSLSocket).startHandshake() }

    private fun headRequest(host: String, port: Int): ByteArray =
        (
            "HEAD / HTTP/1.1\r\n" +
                "Host: $host:$port\r\n" +
                "User-Agent: cloudflared-runner\r\n" +
                "Accept: */*\r\n" +
                "Connection: close\r\n\r\n"
            ).toByteArray(StandardCharsets.ISO_8859_1)

    /** DNS only. Separates "that name does not exist" from "nothing answered", which read differently. */
    fun resolves(host: String): Boolean = try {
        InetAddress.getByName(host)
        true
    } catch (e: UnknownHostException) {
        thisLogger().debug("Lookup of $host failed", e)
        false
    }

    /**
     * Every address behind the host, so a dual-stack name is not reduced to whichever family the
     * resolver happened to put first. Falls back to whatever the eager lookup in [socketAddress]
     * already produced, which is null only for a name that does not resolve at all.
     */
    private fun candidates(target: InetSocketAddress): List<InetAddress> = try {
        InetAddress.getAllByName(target.hostString).toList()
    } catch (e: UnknownHostException) {
        thisLogger().debug("Lookup of ${target.hostString} failed", e)
        listOfNotNull(target.address)
    }

    private fun schemePort(address: String): Int? = when {
        address.startsWith("https://") -> HTTPS_PORT
        address.startsWith("http://") -> HTTP_PORT
        else -> null
    }
}
