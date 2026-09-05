package acr.browser.lightning.browser.devtools.export

import acr.browser.lightning.browser.devtools.net.RecordedEntry

/**
 * Renders a recorded request as a runnable `curl` command.
 *
 * The output is meant to be pasted into a terminal or an HTTP client and executed unchanged, so
 * it is deliberately **not** redacted: a request stripped of its cookies and `Authorization`
 * header would simply fail, which defeats the purpose. Callers are expected to warn the user that
 * what they just copied is a live credential.
 */
object CurlBuilder {

    /**
     * Headers that must not be reproduced.
     *
     * `Content-Length` and `Host` are recomputed by curl, and emitting stale values causes the
     * request to hang or be rejected. `Accept-Encoding` is replaced by `--compressed`, which is
     * what Chrome's own "Copy as cURL" does. The rest are hop-by-hop and meaningless off the
     * original connection.
     */
    private val SKIPPED_HEADERS = setOf(
        "content-length",
        "host",
        "accept-encoding",
        "connection",
        "keep-alive",
        "transfer-encoding",
        "te",
        "trailer",
        "upgrade",
        "proxy-connection"
    )

    /**
     * Build the command.
     *
     * @param cookieHeader cookies to attach when the entry carries none of its own. Requests
     *   captured by the page agent never do: `Cookie` is a forbidden header name in the fetch and
     *   XHR APIs, and HttpOnly cookies are invisible to scripts by design. Supplying the value
     *   from the WebView's own cookie store is the only way the exported command can authenticate.
     */
    fun build(
        entry: RecordedEntry,
        cookieHeader: String? = null,
        multiline: Boolean = true
    ): String {
        val parts = mutableListOf("curl ${quote(entry.url)}")

        // curl defaults to GET, and to POST when a body is present, so -X is only needed when the
        // method is something neither of those defaults produce.
        val method = entry.method.uppercase()
        val impliedMethod = if (entry.requestBody != null) "POST" else "GET"
        if (method != impliedMethod) {
            parts += "-X $method"
        }

        val recorded = entry.requestHeaders.filterNot { header ->
            // HTTP/2 pseudo-headers such as :authority are protocol-level and not sendable.
            header.name.startsWith(":") || header.name.lowercase() in SKIPPED_HEADERS
        }
        recorded.forEach { parts += "-H ${quote("${it.name}: ${it.value}")}" }

        val hasRecordedCookie = recorded.any { it.name.equals("Cookie", ignoreCase = true) }
        if (!hasRecordedCookie && !cookieHeader.isNullOrBlank()) {
            parts += "-H ${quote("Cookie: $cookieHeader")}"
        }

        entry.requestBody?.let { parts += "--data-raw ${quote(it)}" }

        // Ask for compression, since Accept-Encoding was dropped above; curl transparently
        // decodes the response so the output stays readable.
        parts += "--compressed"

        return if (multiline) parts.joinToString(" \\\n  ") else parts.joinToString(" ")
    }

    /**
     * Wrap a value in single quotes for POSIX shells.
     *
     * Inside single quotes every character is literal, so the only thing needing care is a single
     * quote itself: the string is closed, an escaped quote is emitted, and the string reopened.
     * Newlines survive verbatim, which is what a multi-line JSON body needs.
     */
    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
