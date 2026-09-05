package acr.browser.lightning.browser.devtools.bridge

import acr.browser.lightning.browser.devtools.console.ConsoleLog
import acr.browser.lightning.browser.devtools.console.ConsoleMessage
import acr.browser.lightning.browser.devtools.har.HarHeader
import acr.browser.lightning.browser.devtools.har.HarTimings
import acr.browser.lightning.browser.devtools.net.NetworkRecorder
import acr.browser.lightning.browser.devtools.net.RecordedEntry
import acr.browser.lightning.di.BrowserScope
import acr.browser.lightning.js.DevToolsAgent
import acr.browser.lightning.log.Logger
import android.net.Uri
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

/**
 * Installs the page agent into a [WebView] and routes its messages into the recorder.
 *
 * Uses the modern `androidx.webkit` channel rather than `addJavascriptInterface`, which exposes a
 * Java object to every frame on the page — a long-standing injection risk. The web message
 * listener is origin-scoped and passes only strings, so a hostile page can at worst send us
 * malformed JSON, which is parsed defensively below.
 */
@BrowserScope
class DevToolsBridge @Inject constructor(
    private val networkRecorder: NetworkRecorder,
    private val consoleLog: ConsoleLog,
    private val devToolsAgent: DevToolsAgent,
    private val logger: Logger
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val idCounter = AtomicLong(0)

    /** True when this device's WebView supports both APIs the agent needs. */
    val isSupported: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)

    /**
     * Attach the agent to a WebView.
     *
     * Safe to call for every tab as it is created; the agent itself is idempotent and returns
     * early if a page somehow evaluates it twice.
     */
    fun install(webView: WebView) {
        if (!isSupported) {
            logger.log(TAG, "WebView too old for the page agent; native capture only")
            return
        }

        runCatching {
            WebViewCompat.addWebMessageListener(
                webView,
                BRIDGE_NAME,
                ALLOWED_ORIGINS
            ) { _: WebView, message: WebMessageCompat, _: Uri, _: Boolean, _: JavaScriptReplyProxy ->
                message.data?.let(::handleMessage)
            }

            // Document-start injection is what makes the log complete: a script added at
            // onPageFinished would miss every request the page made while loading.
            WebViewCompat.addDocumentStartJavaScript(
                webView,
                devToolsAgent.provideJs(),
                ALLOWED_ORIGINS
            )
        }.onFailure {
            logger.log(TAG, "Could not install the page agent", it)
        }
    }

    private fun handleMessage(raw: String) {
        runCatching {
            val root = json.parseToJsonElement(raw).jsonObject
            when (root.string("type")) {
                "console" -> handleConsole(root)
                "xhr", "fetch" -> handleXhr(root)
                "pageTimings" -> handlePageTimings(root)
                // Resource timings are received but not yet merged; the native layer already
                // provides authoritative timings for everything it replays.
                "resource" -> Unit
                else -> Unit
            }
        }.onFailure {
            logger.log(TAG, "Malformed agent message", it)
        }
    }

    private fun handleConsole(root: JsonObject) {
        consoleLog.add(
            ConsoleMessage(
                level = ConsoleMessage.Level.from(root.string("level")),
                text = root.string("text").orEmpty(),
                timestampMillis = root.double("timestamp")?.toLong() ?: System.currentTimeMillis()
            )
        )
    }

    private fun handleXhr(root: JsonObject) {
        val startedAt = root.double("startedAt")?.toLong() ?: System.currentTimeMillis()
        val duration = root.double("durationMillis") ?: 0.0
        val mimeType = root.string("mimeType").orEmpty().substringBefore(';').trim()

        networkRecorder.addAgentEntry(
            RecordedEntry(
                id = "agent_${idCounter.incrementAndGet()}",
                pageRef = networkRecorder.pages.value.lastOrNull()?.ref ?: "page_0",
                startedWallClockMillis = startedAt,
                method = root.string("method") ?: "GET",
                url = root.string("url").orEmpty(),
                protocol = "",
                requestBody = root.string("requestBody"),
                requestBodyMimeType = mimeType.ifEmpty { null },
                status = root.int("status") ?: 0,
                statusText = root.string("statusText").orEmpty(),
                responseHeaders = root.headers("responseHeaders"),
                mimeType = mimeType,
                bodyText = root.string("responseBody"),
                bodyEncoding = null,
                decodedBodySize = root.string("responseBody")?.length?.toLong() ?: -1,
                // The agent measures wall-clock duration only; it cannot break the request into
                // phases, so everything is attributed to `wait` rather than invented.
                timings = HarTimings(send = 0.0, wait = duration, receive = 0.0),
                resourceType = "xhr",
                source = RecordedEntry.Source.AGENT,
                error = root.string("error")
            )
        )
    }

    private fun handlePageTimings(root: JsonObject) {
        val pageRef = networkRecorder.pages.value.lastOrNull()?.ref ?: return
        networkRecorder.onPageTimings(
            pageRef = pageRef,
            onContentLoadMillis = root.double("onContentLoad") ?: -1.0,
            onLoadMillis = root.double("onLoad") ?: -1.0
        )
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.double(key: String): Double? =
        this[key]?.jsonPrimitive?.doubleOrNull

    private fun JsonObject.int(key: String): Int? =
        this[key]?.jsonPrimitive?.intOrNull

    private fun JsonObject.headers(key: String): List<HarHeader> =
        runCatching {
            this[key]?.jsonArray?.mapNotNull {
                val obj = it.jsonObject
                val name = obj.string("name") ?: return@mapNotNull null
                HarHeader(name, obj.string("value").orEmpty())
            }.orEmpty()
        }.getOrDefault(emptyList())

    private companion object {
        const val TAG = "DevToolsBridge"

        /** Must match the BRIDGE constant in DevToolsAgent.js. */
        const val BRIDGE_NAME = "__lightningDevTools"

        /**
         * The agent must run on every page the user inspects, so all origins are allowed. This is
         * acceptable because the channel is one-way reporting into our own process and carries no
         * privileged capability back to the page.
         */
        val ALLOWED_ORIGINS = setOf("*")
    }
}
