package acr.browser.lightning.browser.devtools.net

import acr.browser.lightning.browser.devtools.har.HarHeader
import acr.browser.lightning.browser.devtools.har.HarTimings
import acr.browser.lightning.di.BrowserScope
import acr.browser.lightning.log.Logger
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

/**
 * Records network traffic for the browser and exposes it for live inspection and HAR export.
 *
 * ## Why requests are replayed through OkHttp
 *
 * [android.webkit.WebViewClient.shouldInterceptRequest] tells us a request is *about* to happen
 * but reveals nothing about how it went: no status, no response headers, no timings, no body. The
 * only way to obtain those from native code is to perform the request ourselves and hand the
 * result back to the WebView. That is what [intercept] does when recording is enabled.
 *
 * Because this changes how traffic is fetched, recording is **opt-in**. While [isRecording] is
 * false, [intercept] returns null immediately and the WebView's own network stack is used
 * untouched, so normal browsing carries none of the risk or overhead.
 *
 * ## Threading
 *
 * [intercept] is called by the WebView on a background thread, once per subresource, and several
 * calls may be in flight at once. All mutable state here is therefore either atomic or confined
 * to a [MutableStateFlow] updated with compare-and-set.
 */
@BrowserScope
class NetworkRecorder @Inject constructor(
    private val logger: Logger
) {

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    /** When true, entries survive navigation; when false, each navigation clears the log. */
    private val _preserveLog = MutableStateFlow(true)
    val preserveLog: StateFlow<Boolean> = _preserveLog.asStateFlow()

    private val _entries = MutableStateFlow<List<RecordedEntry>>(emptyList())
    val entries: StateFlow<List<RecordedEntry>> = _entries.asStateFlow()

    private val _pages = MutableStateFlow<List<RecordedPage>>(emptyList())
    val pages: StateFlow<List<RecordedPage>> = _pages.asStateFlow()

    private val idCounter = AtomicLong(0)

    @Volatile
    private var currentPageRef: String = "page_0"

    /**
     * A client dedicated to recording.
     *
     * Redirects are **not** followed, so each hop comes back to the WebView and is recorded as its
     * own HAR entry, which also keeps the address bar in step on main-frame redirects. Caching is
     * disabled because a cache hit would produce an entry with no real timings.
     */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .eventListenerFactory(TimingEventListener.Factory { call, listener ->
                timings[call] = listener
            })
            .build()
    }

    private val timings = java.util.concurrent.ConcurrentHashMap<Call, TimingEventListener>()

    fun setRecording(enabled: Boolean) {
        _isRecording.value = enabled
    }

    fun setPreserveLog(enabled: Boolean) {
        _preserveLog.value = enabled
    }

    fun clear() {
        _entries.value = emptyList()
        _pages.value = emptyList()
    }

    /**
     * Begin a new page group. Called on main-frame navigation so HAR entries can be attributed to
     * the page that caused them, which is what makes the waterfall readable.
     */
    fun onPageStarted(url: String, title: String) {
        val ref = "page_${idCounter.incrementAndGet()}"
        currentPageRef = ref
        if (!_preserveLog.value) {
            _entries.value = emptyList()
            _pages.value = emptyList()
        }
        _pages.update { it + RecordedPage(ref, System.currentTimeMillis(), url, title) }
    }

    /** Record the load milestones reported by the page agent, for HAR `pageTimings`. */
    fun onPageTimings(pageRef: String, onContentLoadMillis: Double, onLoadMillis: Double) {
        _pages.update { pages ->
            pages.map {
                if (it.ref == pageRef) {
                    it.copy(onContentLoadMillis = onContentLoadMillis, onLoadMillis = onLoadMillis)
                } else {
                    it
                }
            }
        }
    }

    /** Add an entry contributed by the injected page agent (used for request bodies and XHR). */
    fun addAgentEntry(entry: RecordedEntry) = record(entry)

    /**
     * Intercept a WebView request, perform it via OkHttp, record it, and return the response.
     *
     * Returns null whenever recording is off or the request is not something we can faithfully
     * replay, in which case the WebView falls back to its own stack. Any failure here degrades to
     * null rather than propagating, because a bug in the recorder must never stop a page loading.
     */
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        if (!_isRecording.value) return null
        if (!isReplayable(request)) return null

        val url = request.url.toString()
        val startedAt = System.currentTimeMillis()

        return try {
            val call = client.newCall(buildRequest(request))
            val response = call.execute()
            buildInterceptedResponse(request, call, response, url, startedAt)
        } catch (e: Exception) {
            // Record the failure so it is visible in the panel, then let the WebView retry itself.
            recordFailure(url, request.method, startedAt, e)
            logger.log(TAG, "Falling back to WebView stack for $url", e)
            null
        }
    }

    /**
     * Only plain http(s) sub-resource fetches are replayable.
     *
     * WebSocket upgrades are excluded because a [WebResourceResponse] cannot carry one; they are
     * captured by the page agent instead.
     */
    private fun isReplayable(request: WebResourceRequest): Boolean {
        val scheme = request.url.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        if (request.requestHeaders["Upgrade"]?.equals("websocket", ignoreCase = true) == true) {
            return false
        }
        return true
    }

    private fun buildRequest(request: WebResourceRequest): Request {
        val builder = Request.Builder().url(request.url.toString())

        request.requestHeaders.forEach { (name, value) ->
            // Dropping Accept-Encoding lets OkHttp negotiate gzip and decompress transparently,
            // which is what gives us readable bodies for the HAR. Keeping the page's own value
            // (often including brotli) would leave us holding bytes we cannot decode.
            if (!name.equals("Accept-Encoding", ignoreCase = true) && value.isNotEmpty()) {
                builder.header(name, value)
            }
        }

        // The WebView manages cookies in its own store, which OkHttp knows nothing about. Without
        // this bridge every logged-in site would appear signed out while recording.
        CookieManager.getInstance().getCookie(request.url.toString())
            ?.takeIf { it.isNotBlank() }
            ?.let { builder.header("Cookie", it) }

        // shouldInterceptRequest does not expose the request body, so a replayed POST would lose
        // it. Sending an empty body would corrupt the request, so non-GET methods that require a
        // body are handled by the page agent instead and skipped here.
        return if (request.method.equals("GET", ignoreCase = true) ||
            request.method.equals("HEAD", ignoreCase = true)
        ) {
            builder.method(request.method, null).build()
        } else {
            builder.method(request.method, ByteArray(0).toRequestBody()).build()
        }
    }

    private fun buildInterceptedResponse(
        request: WebResourceRequest,
        call: Call,
        response: Response,
        url: String,
        startedAt: Long
    ): WebResourceResponse {
        val listener = timings.remove(call)

        val contentType = response.header("Content-Type").orEmpty()
        val mimeType = contentType.substringBefore(';').trim().ifEmpty { "text/plain" }
        val charset = contentType.substringAfter("charset=", "").substringBefore(';')
            .trim().ifEmpty { "utf-8" }

        val responseHeaders = response.headers.toHarHeaders()

        // Push any Set-Cookie back into the WebView's store, completing the cookie bridge.
        response.headers("Set-Cookie").forEach { CookieManager.getInstance().setCookie(url, it) }

        val bodyStream: InputStream = response.body.byteStream()
        val capture = CapturingInputStream(
            delegate = bodyStream,
            captureLimitBytes = MAX_CAPTURED_BODY_BYTES,
            shouldCapture = isCapturableBody(mimeType)
        ) { captured, truncated, totalBytes ->
            val encodedBody = captured?.encodeForHar(mimeType, charset)
            record(
                RecordedEntry(
                    id = "req_${idCounter.incrementAndGet()}",
                    pageRef = currentPageRef,
                    startedWallClockMillis = startedAt,
                    method = request.method,
                    url = url,
                    protocol = response.protocol.toHarHttpVersion(),
                    requestHeaders = request.requestHeaders.map { HarHeader(it.key, it.value) },
                    status = response.code,
                    statusText = response.message.ifBlank { defaultReason(response.code) },
                    responseHeaders = responseHeaders,
                    mimeType = mimeType,
                    bodyText = encodedBody?.first,
                    bodyEncoding = encodedBody?.second,
                    bodyTruncated = truncated,
                    decodedBodySize = totalBytes,
                    transferBodySize = response.header("Content-Length")?.toLongOrNull() ?: -1,
                    timings = listener?.toHarTimings() ?: EMPTY_TIMINGS,
                    serverIpAddress = listener?.serverIpAddress,
                    resourceType = classifyResource(mimeType, request),
                    source = RecordedEntry.Source.NATIVE
                )
            )
        }

        return WebResourceResponse(
            mimeType,
            charset,
            // WebView rejects a status outside 100..599 and an empty reason phrase outright.
            response.code.coerceIn(HTTP_STATUS_MIN, HTTP_STATUS_MAX),
            response.message.ifBlank { defaultReason(response.code) },
            responseHeaders
                .filterNot { it.name.lowercase() in STRIPPED_RESPONSE_HEADERS }
                .associate { it.name to it.value },
            capture
        )
    }

    private fun recordFailure(url: String, method: String, startedAt: Long, e: Exception) {
        record(
            RecordedEntry(
                id = "req_${idCounter.incrementAndGet()}",
                pageRef = currentPageRef,
                startedWallClockMillis = startedAt,
                method = method,
                url = url,
                protocol = "",
                timings = EMPTY_TIMINGS,
                source = RecordedEntry.Source.NATIVE,
                error = e.message ?: e::class.java.simpleName
            )
        )
    }

    private fun record(entry: RecordedEntry) {
        _entries.update { current ->
            // Bound memory: the log is a ring buffer, since a long session on a media-heavy site
            // can otherwise accumulate tens of thousands of entries on a phone.
            if (current.size >= MAX_ENTRIES) {
                current.drop(current.size - MAX_ENTRIES + 1) + entry
            } else {
                current + entry
            }
        }
    }

    private companion object {
        const val TAG = "NetworkRecorder"
        const val MAX_ENTRIES = 1500
        const val MAX_CAPTURED_BODY_BYTES = 1024 * 1024
        const val HTTP_STATUS_MIN = 100
        const val HTTP_STATUS_MAX = 599

        /**
         * Headers that must not be forwarded to the WebView.
         *
         * OkHttp has already decompressed the body, so leaving `Content-Encoding` in place would
         * make the WebView try to gunzip plain bytes and render nothing — the single most common
         * bug in WebView interception. `Content-Length` no longer matches the decompressed body,
         * and the rest are hop-by-hop headers that are meaningless past the connection.
         */
        val STRIPPED_RESPONSE_HEADERS = setOf(
            "content-encoding",
            "content-length",
            "transfer-encoding",
            "connection",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "upgrade"
        )

        val EMPTY_TIMINGS = HarTimings(send = 0.0, wait = 0.0, receive = 0.0)
    }
}

/** A page load, grouping the entries it caused. */
data class RecordedPage(
    val ref: String,
    val startedWallClockMillis: Long,
    val url: String,
    val title: String,
    val onContentLoadMillis: Double = -1.0,
    val onLoadMillis: Double = -1.0
)
