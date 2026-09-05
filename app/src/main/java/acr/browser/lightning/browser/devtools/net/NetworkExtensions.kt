package acr.browser.lightning.browser.devtools.net

import acr.browser.lightning.browser.devtools.har.HarHeader
import android.util.Base64
import android.webkit.WebResourceRequest
import okhttp3.Headers
import okhttp3.Protocol
import java.nio.charset.Charset

/** Flatten OkHttp headers into the HAR list form, preserving repeated names such as Set-Cookie. */
fun Headers.toHarHeaders(): List<HarHeader> =
    (0 until size).map { HarHeader(name(it), value(it)) }

/** Render the negotiated protocol the way HAR expects it, e.g. "http/1.1" or "h2". */
fun Protocol.toHarHttpVersion(): String = when (this) {
    Protocol.HTTP_1_0 -> "http/1.0"
    Protocol.HTTP_1_1 -> "http/1.1"
    Protocol.HTTP_2 -> "h2"
    Protocol.H2_PRIOR_KNOWLEDGE -> "h2"
    Protocol.QUIC -> "http/3"
    else -> toString()
}

/**
 * A reason phrase for responses that arrive without one.
 *
 * HTTP/2 removes the reason phrase entirely, so this is the common case on modern sites — and
 * [android.webkit.WebResourceResponse] rejects an empty phrase, so a fallback is mandatory.
 */
fun defaultReason(code: Int): String = when (code) {
    200 -> "OK"
    201 -> "Created"
    204 -> "No Content"
    301 -> "Moved Permanently"
    302 -> "Found"
    303 -> "See Other"
    304 -> "Not Modified"
    307 -> "Temporary Redirect"
    308 -> "Permanent Redirect"
    400 -> "Bad Request"
    401 -> "Unauthorized"
    403 -> "Forbidden"
    404 -> "Not Found"
    429 -> "Too Many Requests"
    500 -> "Internal Server Error"
    502 -> "Bad Gateway"
    503 -> "Service Unavailable"
    504 -> "Gateway Timeout"
    else -> "Status $code"
}

/**
 * Whether a body of this type is worth retaining.
 *
 * Media is streamed rather than captured: a video body would blow the memory cap for no benefit,
 * since nobody inspects an MP4 in a network panel.
 */
fun isCapturableBody(mimeType: String): Boolean {
    val type = mimeType.lowercase()
    return !(type.startsWith("video/") || type.startsWith("audio/"))
}

/** True for types whose bytes are meaningful as text, and so should not be base64-encoded. */
fun isTextualMimeType(mimeType: String): Boolean {
    val type = mimeType.lowercase()
    return type.startsWith("text/") ||
        type.endsWith("+json") ||
        type.endsWith("+xml") ||
        type == "application/json" ||
        type == "application/javascript" ||
        type == "application/x-javascript" ||
        type == "application/ecmascript" ||
        type == "application/xml" ||
        type == "application/xhtml+xml" ||
        type == "application/x-www-form-urlencoded" ||
        type == "image/svg+xml"
}

/**
 * Encode a captured body for the HAR `content` object.
 *
 * @return the encoded text and the HAR `encoding` value — null for plain text, "base64" for
 *   binary, matching what Chrome DevTools and Charles expect on import.
 */
fun ByteArray.encodeForHar(mimeType: String, charsetName: String): Pair<String, String?> =
    if (isTextualMimeType(mimeType)) {
        val charset = runCatching { Charset.forName(charsetName) }.getOrDefault(Charsets.UTF_8)
        toString(charset) to null
    } else {
        Base64.encodeToString(this, Base64.NO_WRAP) to "base64"
    }

/**
 * Classify a request for the network panel's filter chips and Chrome's `_resourceType` field.
 *
 * The MIME type is the more reliable signal, so it is consulted first; the request's own flags
 * only settle the document-versus-subresource question that MIME cannot answer.
 */
fun classifyResource(mimeType: String, request: WebResourceRequest): String {
    val type = mimeType.lowercase()
    return when {
        request.isForMainFrame && type.contains("html") -> "document"
        type.startsWith("image/") -> "image"
        type.startsWith("font/") || type.contains("woff") || type.contains("ttf") -> "font"
        type.startsWith("video/") || type.startsWith("audio/") -> "media"
        type.contains("css") -> "stylesheet"
        type.contains("javascript") || type.contains("ecmascript") -> "script"
        type.contains("json") -> "xhr"
        type.contains("html") -> "document"
        type.contains("xml") -> "xhr"
        else -> "other"
    }
}
