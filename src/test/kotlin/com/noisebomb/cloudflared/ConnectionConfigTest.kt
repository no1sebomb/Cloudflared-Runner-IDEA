package com.noisebomb.cloudflared

import com.noisebomb.cloudflared.model.AccessProtocol
import com.noisebomb.cloudflared.model.ConnectionConfig
import com.noisebomb.cloudflared.model.ConnectionType
import com.noisebomb.cloudflared.model.EdgeIpVersion
import com.noisebomb.cloudflared.model.LogLevel
import com.noisebomb.cloudflared.model.TunnelProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionConfigTest {

    @Test
    fun `leaves every unset advanced option off the quick tunnel command`() {
        val config = ConnectionConfig(type = ConnectionType.QUICK_TUNNEL, target = "localhost:8080")
        assertEquals(listOf("cloudflared", "tunnel", "--url", "localhost:8080"), config.commandLine("cloudflared"))
    }

    @Test
    fun `builds a quick tunnel with advanced options`() {
        val config = ConnectionConfig(
            type = ConnectionType.QUICK_TUNNEL,
            target = "localhost:8080",
            postQuantum = true,
            tunnelProtocol = TunnelProtocol.QUIC,
            edgeIpVersion = EdgeIpVersion.V4,
            region = "us",
            httpHostHeader = "app.internal",
            noTlsVerify = true,
            logLevel = LogLevel.DEBUG,
        )
        assertEquals(
            listOf(
                "cloudflared", "tunnel", "--post-quantum", "--protocol", "quic", "--edge-ip-version", "4",
                "--region", "us", "--http-host-header", "app.internal", "--no-tls-verify",
                "--loglevel", "debug", "--url", "localhost:8080",
            ),
            config.commandLine("cloudflared"),
        )
    }

    @Test
    fun `access protocol picks the subcommand`() {
        val config = ConnectionConfig(
            type = ConnectionType.ACCESS_TCP,
            target = "ssh.example.com",
            localBind = "localhost:2222",
            accessProtocol = AccessProtocol.SSH,
            destination = "box-1",
        )
        assertEquals(
            listOf(
                "cloudflared", "access", "ssh", "--hostname", "ssh.example.com",
                "--url", "localhost:2222", "--destination", "box-1",
            ),
            config.commandLine("cloudflared"),
        )
    }

    @Test
    fun `keeps cloudflared's own defaults off the command line`() {
        val config = ConnectionConfig(
            type = ConnectionType.QUICK_TUNNEL,
            target = "localhost:8080",
            retries = ConnectionConfig.DEFAULT_RETRIES,
            logLevel = LogLevel.DEFAULT,
            tunnelProtocol = TunnelProtocol.AUTO,
            edgeIpVersion = EdgeIpVersion.AUTO,
        )
        assertEquals(listOf("cloudflared", "tunnel", "--url", "localhost:8080"), config.commandLine("cloudflared"))
        assertEquals(
            listOf("cloudflared", "tunnel", "--socks5", "--no-autoupdate", "--retries", "9", "--url", "localhost:8080"),
            config.copy(socks5 = true, noAutoUpdate = true, retries = 9).commandLine("cloudflared"),
        )
    }

    @Test
    fun `only treats a schemeless or http target as something the public URL can serve`() {
        val quick = ConnectionConfig(type = ConnectionType.QUICK_TUNNEL)
        assertTrue(quick.copy(target = "localhost:8080").isHttpTarget())
        assertTrue(quick.copy(target = "http://localhost:8080").isHttpTarget())
        assertTrue(quick.copy(target = "HTTPS://localhost:8443").isHttpTarget())
        // A deliberate choice by the user, not a mistake to warn about.
        assertFalse(quick.copy(target = "tcp://localhost:5432").isHttpTarget())
        assertFalse(quick.copy(target = "ssh://localhost:22").isHttpTarget())
    }

    @Test
    fun `falls back to the project executable only when the connection names none`() {
        val config = ConnectionConfig(type = ConnectionType.QUICK_TUNNEL, target = "localhost:8080")
        assertEquals("cloudflared", config.resolveExecutable("cloudflared"))
        assertEquals("/opt/bin/cloudflared", config.copy(executable = "/opt/bin/cloudflared").resolveExecutable("cf"))
    }

    @Test
    fun `suggests a name from whatever the user typed as a target`() {
        assertEquals(
            "localhost:8080",
            ConnectionConfig.suggestName(ConnectionType.QUICK_TUNNEL, "http://localhost:8080/"),
        )
        // A port would be wrong on an Access hostname, and reads badly as a label.
        assertEquals(
            "db.example.com",
            ConnectionConfig.suggestName(ConnectionType.ACCESS_TCP, "https://db.example.com/path"),
        )
        assertEquals("", ConnectionConfig.suggestName(ConnectionType.QUICK_TUNNEL, "  "))
    }
}
