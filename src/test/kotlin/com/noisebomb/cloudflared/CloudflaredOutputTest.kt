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
