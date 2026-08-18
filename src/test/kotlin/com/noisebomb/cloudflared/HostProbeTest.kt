package com.noisebomb.cloudflared

import com.noisebomb.cloudflared.service.HostProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class HostProbeTest {

    @Test
    fun `finds a listener bound to only one of the families localhost resolves to`() {
        // The bug this covers: an eagerly resolved InetSocketAddress pins the probe to whichever of
        // ::1 and 127.0.0.1 the resolver returned first, and a server on the other one reads as down.
        ServerSocket().use { server ->
            server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
            assertTrue(HostProbe.canConnect("localhost:${server.localPort}"))
        }
    }

    @Test
    fun `a port held on one family is not offered as free`() {
        ServerSocket().use { server ->
            server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
            assertFalse(HostProbe.isPortAvailable("localhost:${server.localPort}"))
        }
    }

    @Test
    fun `an unused port is free and answers nothing`() {
        val port = ServerSocket().use {
            it.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
            it.localPort
        }
        assertTrue(HostProbe.isPortAvailable("localhost:$port"))
        assertFalse(HostProbe.canConnect("localhost:$port"))
    }

    @Test
    fun `reads a port off every shape of target the dialog accepts`() {
        assertEquals(3000, HostProbe.socketAddress("localhost:3000")?.port)
        assertEquals(3000, HostProbe.socketAddress("http://localhost:3000")?.port)
        assertEquals(5432, HostProbe.socketAddress("tcp://localhost:5432")?.port)
        assertEquals(80, HostProbe.socketAddress("http://localhost")?.port)
        assertEquals(443, HostProbe.socketAddress("https://localhost")?.port)
        assertEquals(HostProbe.HTTPS_PORT, HostProbe.socketAddress("db.example.com", 443)?.port)
    }

    @Test
    fun `sees HTTP on a server bound to only one family`() {
        // HttpURLConnection connects to a single resolved address, so this is the case that used to
        // tell a Nuxt or Vite dev server it was "answered, but not over HTTP".
        stubServer("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n") { port ->
            assertTrue(HostProbe.speaksHttp("localhost:$port"))
        }
    }

    @Test
    fun `does not call a non-HTTP service HTTP`() {
        stubServer("+PONG\r\n") { port ->
            assertFalse(HostProbe.speaksHttp("localhost:$port"))
        }
    }

    /**
     * Answers [reply] to anything, on `::1` only when this machine has it, so the probe has to walk
     * past the address the resolver returns first to find it.
     */
    private fun stubServer(reply: String, body: (Int) -> Unit) {
        val bindTo = InetAddress.getAllByName("localhost").last()
        ServerSocket().use { server ->
            server.bind(InetSocketAddress(bindTo, 0))
            val accepting = thread(isDaemon = true) {
                runCatching {
                    while (true) {
                        server.accept().use { client: Socket ->
                            client.getOutputStream().apply {
                                write(reply.toByteArray())
                                flush()
                            }
                        }
                    }
                }
            }
            try {
                body(server.localPort)
            } finally {
                accepting.interrupt()
            }
        }
    }

    @Test
    fun `reads a response head down to the blank line that ends it`() {
        val headers = HostProbe.readHeaders(
            """
            Date: Tue, 18 Aug 2026 18:28:34 GMT
            Content-Type: text/html; charset=UTF-8
            Server: Cloudflare
            CF-RAY: 8d3f00000000-AMS

            <!doctype html>
            """.trimIndent().reader().buffered(),
        )

        // Lower-cased both sides: HTTP header names are case-insensitive, and the caller compares
        // the value against "cloudflare".
        assertEquals("cloudflare", headers["server"])
        assertEquals("text/html; charset=utf-8", headers["content-type"])
        // The body is past the blank line and none of the head's business.
        assertFalse(headers.containsKey("<!doctype html>"))
    }

    @Test
    fun `ignores a line that is not a header`() {
        val headers = HostProbe.readHeaders("not a header at all\nServer: nginx\n\n".reader().buffered())

        assertEquals("nginx", headers["server"])
        assertEquals(1, headers.size)
    }
}
