package acr.browser.lightning.browser.devtools.net

import acr.browser.lightning.browser.devtools.har.HarHeader
import acr.browser.lightning.browser.devtools.har.HarTimings

/**
 * One recorded network exchange, held in memory by the [NetworkRecorder] and converted to a
 * `HarEntry` only at export time.
 *
 * Bodies are stored already encoded as [String] rather than as `ByteArray` so that this stays a
 * value type with sane equality, and so the expensive text/base64 decision is made once at
 * capture time instead of on every render of the network list.
 */
data class RecordedEntry(
    val id: String,
    val pageRef: String,
    /** Wall-clock time of the request start, for the HAR `startedDateTime`. */
    val startedWallClockMillis: Long,
    val method: String,
    val url: String,
    val protocol: String,
    val requestHeaders: List<HarHeader> = emptyList(),
    val requestBody: String? = null,
    val requestBodyMimeType: String? = null,
    val status: Int = 0,
    val statusText: String = "",
    val responseHeaders: List<HarHeader> = emptyList(),
    val mimeType: String = "",
    /** Response body, UTF-8 text or base64 depending on [bodyEncoding]. Null if not retained. */
    val bodyText: String? = null,
    /** "base64" when [bodyText] is base64-encoded, null when it is plain text. */
    val bodyEncoding: String? = null,
    /** True when the body exceeded the capture cap and was cut short. */
    val bodyTruncated: Boolean = false,
    /** Decompressed body length in bytes. */
    val decodedBodySize: Long = -1,
    /** On-the-wire body length in bytes, before decompression. */
    val transferBodySize: Long = -1,
    val timings: HarTimings,
    val serverIpAddress: String? = null,
    val resourceType: String = "other",
    val source: Source,
    val fromCache: Boolean = false,
    /** Set when the exchange failed; the entry is still recorded so failures are visible. */
    val error: String? = null
) {

    /** Which capture layer produced this entry. */
    enum class Source {
        /** Intercepted natively and replayed through OkHttp. Authoritative for headers/timings. */
        NATIVE,

        /** Reported by the injected page agent. Authoritative for request bodies. */
        AGENT
    }

    /** Total elapsed time in milliseconds, as the HAR `entry.time` field. */
    val totalTimeMillis: Double get() = timings.total()

    val isFailure: Boolean get() = error != null || status >= 400
}
