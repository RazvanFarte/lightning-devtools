package acr.browser.lightning.browser.devtools.net

import acr.browser.lightning.browser.devtools.har.HarTimings
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.HttpUrl
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * Collects per-call phase timings from OkHttp and converts them into the HAR `timings` object.
 *
 * OkHttp's callbacks line up almost exactly with the HAR timing model, which is why the recorder
 * replays requests through OkHttp rather than trying to time them from the WebView side — the
 * WebView gives no timing information to native code at all.
 *
 * One instance is created per call by [Factory], so no synchronisation is needed here: OkHttp
 * guarantees the callbacks for a single call are not concurrent.
 */
class TimingEventListener : EventListener() {

    private var callStart = -1L
    private var dnsStart = -1L
    private var dnsEnd = -1L
    private var connectStart = -1L
    private var connectEnd = -1L
    private var secureConnectStart = -1L
    private var secureConnectEnd = -1L
    private var requestHeadersStart = -1L
    private var requestEnd = -1L
    private var responseHeadersStart = -1L
    private var responseEnd = -1L

    /** Set when the connection was reused, in which case there is no DNS or connect phase. */
    private var connectionAcquired = -1L

    var serverIpAddress: String? = null
        private set

    var protocol: Protocol? = null
        private set

    override fun callStart(call: Call) {
        callStart = now()
    }

    override fun dnsStart(call: Call, domainName: String) {
        dnsStart = now()
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        dnsEnd = now()
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        connectStart = now()
        serverIpAddress = inetSocketAddress.address?.hostAddress
    }

    override fun secureConnectStart(call: Call) {
        secureConnectStart = now()
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        secureConnectEnd = now()
    }

    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?
    ) {
        connectEnd = now()
        this.protocol = protocol
    }

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException
    ) {
        connectEnd = now()
    }

    override fun connectionAcquired(call: Call, connection: Connection) {
        connectionAcquired = now()
        if (protocol == null) {
            protocol = connection.protocol()
        }
        if (serverIpAddress == null) {
            serverIpAddress = connection.socket().inetAddress?.hostAddress
        }
    }

    override fun requestHeadersStart(call: Call) {
        requestHeadersStart = now()
    }

    override fun requestHeadersEnd(call: Call, request: Request) {
        requestEnd = now()
    }

    override fun requestBodyEnd(call: Call, byteCount: Long) {
        requestEnd = now()
    }

    override fun responseHeadersStart(call: Call) {
        responseHeadersStart = now()
    }

    override fun responseBodyEnd(call: Call, byteCount: Long) {
        responseEnd = now()
    }

    override fun callEnd(call: Call) {
        if (responseEnd < 0) responseEnd = now()
    }

    override fun callFailed(call: Call, ioe: IOException) {
        if (responseEnd < 0) responseEnd = now()
    }

    /**
     * Build the HAR timings.
     *
     * Phases that did not occur are reported as -1, which the spec defines as "does not apply" —
     * a reused connection legitimately has no `dns` or `connect` phase, and reporting 0 there
     * would wrongly suggest an instantaneous lookup.
     *
     * `ssl` is deliberately nested inside `connect` rather than added alongside it, because the
     * spec counts TLS time as part of the connect phase and viewers double-count otherwise.
     */
    fun toHarTimings(): HarTimings {
        // Everything before the first network activity is "blocked" (queueing, pool wait).
        val firstActivity = listOf(dnsStart, connectStart, connectionAcquired, requestHeadersStart)
            .filter { it >= 0 }
            .minOrNull()

        val blocked = span(callStart, firstActivity ?: -1L)
        val dns = span(dnsStart, dnsEnd)
        val connect = span(connectStart, connectEnd)
        val ssl = span(secureConnectStart, secureConnectEnd)
        val send = span(requestHeadersStart, requestEnd)
        val wait = span(requestEnd, responseHeadersStart)
        val receive = span(responseHeadersStart, responseEnd)

        return HarTimings(
            blocked = blocked,
            dns = dns,
            connect = connect,
            // send/wait/receive are mandatory in the spec, so they fall back to 0 rather than -1.
            send = send.coerceAtLeast(0.0),
            wait = wait.coerceAtLeast(0.0),
            receive = receive.coerceAtLeast(0.0),
            ssl = ssl
        )
    }

    private fun span(start: Long, end: Long): Double =
        if (start >= 0 && end >= start) (end - start) / NANOS_PER_MILLI else -1.0

    private fun now(): Long = System.nanoTime()

    /** Creates a fresh listener per call and hands it to [onCreated] so the caller can read it. */
    class Factory(private val onCreated: (Call, TimingEventListener) -> Unit) : EventListener.Factory {
        override fun create(call: Call): EventListener =
            TimingEventListener().also { onCreated(call, it) }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000.0
    }
}
