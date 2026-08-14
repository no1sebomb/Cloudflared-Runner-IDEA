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

    /** Headless or expired-token case: cloudflared prints an SSO URL for the user to open manually. */
    private val ACCESS_LOGIN_URL = Regex("""https://\S+/cdn-cgi/access/(cli|login)""")

    fun findTunnelUrl(text: String): String? = TRYCLOUDFLARE_URL.find(text)?.value

    fun hasLoginPrompt(text: String): Boolean = ACCESS_LOGIN_URL.containsMatchIn(text)
}
