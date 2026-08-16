package com.noisebomb.cloudflared

import com.noisebomb.cloudflared.model.ConnectionConfig
import com.noisebomb.cloudflared.model.ConnectionType
import com.noisebomb.cloudflared.service.CloudflaredOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflaredOutputTest {

    @Test
    fun `picks the generated hostname out of the ascii box`() {
        val line = "|  https://polite-tiger-sees-fox.trycloudflare.com                       |"
        assertEquals("https://polite-tiger-sees-fox.trycloudflare.com", CloudflaredOutput.findTunnelUrl(line))
    }

    @Test
    fun `ignores the api endpoint mentioned in failure output`() {
        // Real output from a quick tunnel that could not reach Cloudflare.
        val line = "failed to request quick Tunnel: Post \"https://api.trycloudflare.com/tunnel\": " +
            "context deadline exceeded (Client.Timeout exceeded while awaiting headers)"
        assertNull(CloudflaredOutput.findTunnelUrl(line))
    }

    @Test
    fun `ignores the startup banner`() {
        val line = "INF Requesting new quick Tunnel on trycloudflare.com..."
        assertNull(CloudflaredOutput.findTunnelUrl(line))
    }

    @Test
    fun `detects a manual login url`() {
        val line = "Please open the following URL: https://team.cloudflareaccess.com/cdn-cgi/access/cli?..."
        assertTrue(CloudflaredOutput.hasLoginPrompt(line))
        assertFalse(CloudflaredOutput.hasLoginPrompt("INF Start Websocket listener host=localhost:5433"))
    }

    @Test
    fun `counts the reprint for a queued login as a prompt`() {
        // What a second connection gets while the first login is still outstanding.
        val line = "Another cloudflared process (pid 196108) is already waiting for authentication."
        assertTrue(CloudflaredOutput.hasLoginPrompt(line))
        val fallback = "If a browser window did not open, please visit the following URL:"
        assertTrue(CloudflaredOutput.hasLoginPrompt(fallback))
    }

    @Test
    fun `keeps the query string of a login url`() {
        val line = "Please open the following URL: https://team.cloudflareaccess.com/cdn-cgi/access/cli?aud=abc&t=1."
        assertEquals(
            "https://team.cloudflareaccess.com/cdn-cgi/access/cli?aud=abc&t=1",
            CloudflaredOutput.findLoginUrl(line),
        )
        assertNull(CloudflaredOutput.findLoginUrl("INF Start Websocket listener host=localhost:5433"))
    }

    @Test
    fun `detects the access listener coming up`() {
        assertTrue(CloudflaredOutput.hasAccessListener("INF Start Websocket listener host=localhost:5433"))
        assertFalse(CloudflaredOutput.hasAccessListener("INF Requesting new quick Tunnel"))
    }

    @Test
    fun `detects an origin the access client cannot reach`() {
        val line = "2026-08-16T11:59:19Z ERR failed to connect to origin " +
            "error=\"dial tcp: lookup db.example.com on 127.0.0.53:53: no such host\" " +
            "originURL=https://db.example.com"
        assertTrue(CloudflaredOutput.hasOriginFailure(line))
        assertEquals("Hostname could not be resolved", CloudflaredOutput.summarize(line, -1))
        assertFalse(CloudflaredOutput.hasOriginFailure("INF Start Websocket listener host=localhost:5433"))
    }

    @Test
    fun `summarizes known failures`() {
        val bindFailure = "failed to start forwarder: listen tcp 127.0.0.1:5433: bind: address already in use"
        assertEquals("Port :5433 is already in use", CloudflaredOutput.summarize(bindFailure, 1))
        // No port to name: the generic wording still beats a page of Go internals.
        assertEquals("Local port is already in use", CloudflaredOutput.summarize("bind: address already in use", 1))
        assertEquals(
            "cloudflared not found on PATH",
            CloudflaredOutput.summarize("Cannot run program \"cloudflared\": error=2, No such file or directory", -1),
        )
        assertEquals(
            "Could not reach Cloudflare",
            CloudflaredOutput.summarize("failed to request quick Tunnel: context deadline exceeded", 1),
        )
    }

    @Test
    fun `falls back to the last stderr line without its log prefix`() {
        val line = "2026-08-15T10:11:12Z ERR something nobody predicted"
        assertEquals("something nobody predicted", CloudflaredOutput.summarize(line, 1))
    }

    @Test
    fun `falls back to the exit code when the process said nothing`() {
        assertEquals("Exited with code 7", CloudflaredOutput.summarize("", 7))
    }

    @Test
    fun `builds the quick tunnel argument vector`() {
        val config = ConnectionConfig(type = ConnectionType.QUICK_TUNNEL, target = "localhost:8080")
        assertEquals(listOf("cloudflared", "tunnel", "--url", "localhost:8080"), config.commandLine("cloudflared"))
    }

    @Test
    fun `builds the access client argument vector`() {
        val config = ConnectionConfig(
            type = ConnectionType.ACCESS_TCP,
            target = "db.example.com",
            localBind = "localhost:5433",
        )
        assertEquals(
            listOf("cloudflared", "access", "tcp", "--hostname", "db.example.com", "--url", "localhost:5433"),
            config.commandLine("cloudflared"),
        )
    }
}
