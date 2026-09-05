package acr.browser.lightning.browser.devtools.har

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * HAR 1.2 archive model, as specified by the W3C Web Archive draft.
 *
 * Field names and nullability follow the spec exactly so that the JSON this produces loads in
 * Chrome DevTools, Firefox, Charles, Fiddler and the online HAR viewers without massaging.
 * Anything we add beyond the spec is prefixed with an underscore, which the spec reserves for
 * custom fields and which every reader is required to ignore.
 */
@Serializable
data class HarArchive(
    val log: HarLog
)

@Serializable
data class HarLog(
    val version: String = "1.2",
    val creator: HarCreator,
    val browser: HarCreator? = null,
    val pages: List<HarPage> = emptyList(),
    val entries: List<HarEntry> = emptyList(),
    val comment: String? = null
)

@Serializable
data class HarCreator(
    val name: String,
    val version: String,
    val comment: String? = null
)

@Serializable
data class HarPage(
    val startedDateTime: String,
    val id: String,
    val title: String,
    val pageTimings: HarPageTimings,
    val comment: String? = null
)

/** Milliseconds from page start; -1 means "not applicable / not measured". */
@Serializable
data class HarPageTimings(
    val onContentLoad: Double = -1.0,
    val onLoad: Double = -1.0,
    val comment: String? = null
)

@Serializable
data class HarEntry(
    val pageref: String? = null,
    val startedDateTime: String,
    /** Total elapsed time in ms. Must equal the sum of the non-negative [timings] fields. */
    val time: Double,
    val request: HarRequest,
    val response: HarResponse,
    val cache: HarCache = HarCache(),
    val timings: HarTimings,
    val serverIPAddress: String? = null,
    val connection: String? = null,
    val comment: String? = null,
    /** Chrome-compatible custom field; DevTools uses it to group the network list. */
    @SerialName("_resourceType") val resourceType: String? = null,
    /** Which capture layer produced this entry, for our own diagnostics. */
    @SerialName("_source") val source: String? = null,
    @SerialName("_initiator") val initiator: String? = null,
    @SerialName("_fromCache") val fromCache: String? = null
)

@Serializable
data class HarRequest(
    val method: String,
    val url: String,
    val httpVersion: String,
    val cookies: List<HarCookie> = emptyList(),
    val headers: List<HarHeader> = emptyList(),
    val queryString: List<HarQueryParam> = emptyList(),
    val postData: HarPostData? = null,
    /**
     * Size of the request headers in bytes, including the trailing CRLF.
     * -1 when unknown, which the spec permits and readers handle.
     */
    val headersSize: Long = -1,
    val bodySize: Long = -1,
    val comment: String? = null
)

@Serializable
data class HarResponse(
    val status: Int,
    val statusText: String,
    val httpVersion: String,
    val cookies: List<HarCookie> = emptyList(),
    val headers: List<HarHeader> = emptyList(),
    val content: HarContent,
    val redirectURL: String = "",
    val headersSize: Long = -1,
    /**
     * Size of the received body in bytes, i.e. after transfer encoding but before decompression.
     * 0 for responses served from cache.
     */
    val bodySize: Long = -1,
    val comment: String? = null
)

@Serializable
data class HarContent(
    /** Length of the returned content in bytes, *decompressed*. */
    val size: Long,
    /** Bytes saved by compression: [size] minus the on-the-wire body size. */
    val compression: Long? = null,
    val mimeType: String = "",
    /**
     * The body itself. Text is stored verbatim; binary bodies are base64 and set [encoding].
     * Null when the body was not retained (see the body size cap in the recorder).
     */
    val text: String? = null,
    /** "base64" when [text] is base64-encoded, absent when it is plain text. */
    val encoding: String? = null,
    val comment: String? = null
)

@Serializable
data class HarCache(
    val beforeRequest: HarCacheState? = null,
    val afterRequest: HarCacheState? = null,
    val comment: String? = null
)

@Serializable
data class HarCacheState(
    val expires: String? = null,
    val lastAccess: String,
    val eTag: String,
    val hitCount: Int,
    val comment: String? = null
)

/**
 * Phase durations in milliseconds. [send], [wait] and [receive] are mandatory; the rest use -1
 * to mean "does not apply to this request" (e.g. no DNS lookup on a reused connection).
 */
@Serializable
data class HarTimings(
    val blocked: Double = -1.0,
    val dns: Double = -1.0,
    val connect: Double = -1.0,
    val send: Double,
    val wait: Double,
    val receive: Double,
    /** Included in [connect] per the spec, so it must never be added again when summing. */
    val ssl: Double = -1.0,
    val comment: String? = null
) {

    /**
     * The HAR spec requires `entry.time` to be the sum of the timings that are not -1, and
     * requires `ssl` to be excluded because it is already counted inside `connect`.
     */
    fun total(): Double = listOf(blocked, dns, connect, send, wait, receive)
        .filter { it >= 0 }
        .sum()
}

@Serializable
data class HarHeader(
    val name: String,
    val value: String,
    val comment: String? = null
)

@Serializable
data class HarQueryParam(
    val name: String,
    val value: String,
    val comment: String? = null
)

@Serializable
data class HarCookie(
    val name: String,
    val value: String,
    val path: String? = null,
    val domain: String? = null,
    val expires: String? = null,
    val httpOnly: Boolean? = null,
    val secure: Boolean? = null,
    val comment: String? = null
)

@Serializable
data class HarPostData(
    val mimeType: String,
    val params: List<HarPostParam> = emptyList(),
    val text: String = "",
    val comment: String? = null
)

@Serializable
data class HarPostParam(
    val name: String,
    val value: String? = null,
    val fileName: String? = null,
    val contentType: String? = null,
    val comment: String? = null
)
