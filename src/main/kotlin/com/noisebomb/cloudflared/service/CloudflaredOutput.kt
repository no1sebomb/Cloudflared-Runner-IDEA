package com.noisebomb.cloudflared.service

/**
 * The few things worth pulling out of cloudflared's output. Everything interesting — the generated
 * hostname, login prompts, errors — goes to stderr, so this runs over both streams.
 */
object CloudflaredOutput {

    /**
     * The generated quick-tunnel hostname, printed inside an ASCII box.
     *
     * The `api.` exclusion and the trailing-character check matter: cloudflared also mentions
     * `https://api.trycloudflare.com/tunnel` when a request *fails*, and matching that would show a
     * dead URL as if the tunnel were up.
     */
    private val TRYCLOUDFLARE_URL = Regex("""https://(?!api\.)[-a-z0-9]+\.trycloudflare\.com(?![-./\w])""")

    /**
     * Headless or expired-token case: cloudflared prints an SSO URL for the user to open manually.
     * The query string carries the one-time token, so it has to come along.
     */
    private val ACCESS_LOGIN_URL = Regex("""https://\S+?/cdn-cgi/access/(?:cli|login)\S*""")

    /** Log lines wrap the URL in quotes or brackets often enough to be worth trimming. */
    private const val URL_TRAILING_JUNK = ".,;:'\")]}"

    /** Same situation, but the wording rather than the URL — printed just above the link. */
    private val ACCESS_LOGIN_PROMPT = Regex(
        """(?i)please open the following url|open the following url in your browser""",
    )

    /**
     * An access client is only useful once its local listener is up. cloudflared announces that as
     * `INF Start Websocket listener host=localhost:5433`.
     */
    private val ACCESS_LISTENER = Regex("""(?i)start websocket listener|start server listener|listening on""")

    /** `2026-08-15T10:11:12Z ERR ` and friends — noise in front of every real message. */
    private val LOG_PREFIX = Regex("""^\S*T\S*Z?\s+(TRC|DBG|INF|WRN|ERR|FTL)\s+""")

    private val ANSI = Regex("\u001B\\[[0-9;]*m")

    /** `listen tcp 127.0.0.1:5433: bind: address already in use` — the port is the useful half. */
    private val PORT_IN_USE = Regex("""(?i):(\d{1,5})\D*?address already in use""")

    /**
     * Failures worth naming. Raw cloudflared errors run to several lines of Go internals, which is
     * far too much for a table cell; anything not listed here falls back to the last stderr line.
     */
    private val ERROR_SUMMARIES: List<Pair<Regex, String>> = listOf(
        Regex("""(?i)cannot run program|error=exec:|executable file not found""") to
            "cloudflared not found on PATH",
        Regex("""(?i)address already in use""") to
            "Local port is already in use",
        Regex("""(?i)connection refused""") to
            "Local service is not accepting connections",
        Regex("""(?i)no such host|name resolution""") to
            "Hostname could not be resolved",
        Regex("""(?i)failed to request quick tunnel|context deadline exceeded|i/o timeout""") to
            "Could not reach Cloudflare",
        Regex("""(?i)\b(401|403)\b|unauthorized|forbidden|bad handshake""") to
            "Access denied — check the Access policy",
        Regex("""(?i)x509|certificate""") to
            "TLS certificate error",
        Regex("""(?i)permission denied""") to
            "Permission denied",
        Regex("""(?i)failed to create tunnel|failed to serve tunnel""") to
            "Cloudflare refused the tunnel",
    )

    fun findTunnelUrl(text: String): String? = TRYCLOUDFLARE_URL.find(text)?.value

    fun hasLoginPrompt(text: String): Boolean =
        ACCESS_LOGIN_URL.containsMatchIn(text) || ACCESS_LOGIN_PROMPT.containsMatchIn(text)

    /** The SSO link itself, so the status cell can be made clickable. */
    fun findLoginUrl(text: String): String? =
        ACCESS_LOGIN_URL.find(text)?.value?.trimEnd(*URL_TRAILING_JUNK.toCharArray())

    fun hasAccessListener(text: String): Boolean = ACCESS_LISTENER.containsMatchIn(text)

    /**
     * One short line for the status column. [stderr] is the last non-empty stderr line seen, which
     * is empty when the process died without saying anything.
     */
    fun summarize(stderr: String, exitCode: Int): String {
        val clean = stderr.replace(ANSI, "").trim()
        PORT_IN_USE.find(clean)?.let { return "Port :${it.groupValues[1]} is already in use" }
        ERROR_SUMMARIES.firstOrNull { (pattern, _) -> pattern.containsMatchIn(clean) }
            ?.let { return it.second }
        val message = clean.replaceFirst(LOG_PREFIX, "").trim()
        if (message.isEmpty()) return "Exited with code $exitCode"
        return if (message.length > MAX_SUMMARY) message.take(MAX_SUMMARY - 1).trimEnd() + "…" else message
    }

    private const val MAX_SUMMARY = 120
}
