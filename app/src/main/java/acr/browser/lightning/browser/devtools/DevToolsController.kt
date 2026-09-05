package acr.browser.lightning.browser.devtools

import acr.browser.lightning.browser.devtools.console.ConsoleLog
import acr.browser.lightning.browser.devtools.console.ConsoleMessage
import acr.browser.lightning.browser.devtools.export.CurlBuilder
import acr.browser.lightning.browser.devtools.har.HarExporter
import acr.browser.lightning.browser.devtools.net.NetworkRecorder
import acr.browser.lightning.browser.devtools.net.RecordedEntry
import acr.browser.lightning.di.BrowserScope
import acr.browser.lightning.log.Logger
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * Owns the DevTools panel's visibility and the actions it offers.
 *
 * Kept separate from [BrowserPresenter][acr.browser.lightning.browser.BrowserPresenter] so the
 * feature can be opened, exported from and dismissed without touching the browser's own state
 * machine.
 */
@BrowserScope
class DevToolsController @Inject constructor(
    val networkRecorder: NetworkRecorder,
    val consoleLog: ConsoleLog,
    private val harExporter: HarExporter,
    private val logger: Logger
) {

    private val _isOpen = MutableStateFlow(false)
    val isOpen: StateFlow<Boolean> = _isOpen.asStateFlow()

    /**
     * Credentials are stripped from exported files unless this is switched off. Defaults to on
     * because an exported HAR is meant to be sent somewhere.
     */
    private val _redactSecrets = MutableStateFlow(true)
    val redactSecrets: StateFlow<Boolean> = _redactSecrets.asStateFlow()

    private val _lastExport = MutableStateFlow<ExportResult?>(null)
    val lastExport: StateFlow<ExportResult?> = _lastExport.asStateFlow()

    /** Opening the panel starts recording, since that is invariably why it was opened. */
    fun open() {
        _isOpen.value = true
        if (!networkRecorder.isRecording.value) {
            networkRecorder.setRecording(true)
        }
    }

    fun close() {
        _isOpen.value = false
    }

    fun toggleRecording() {
        networkRecorder.setRecording(!networkRecorder.isRecording.value)
    }

    fun toggleRedaction() {
        _redactSecrets.value = !_redactSecrets.value
    }

    fun clear() {
        networkRecorder.clear()
        consoleLog.clear()
    }

    fun consumeExportResult() {
        _lastExport.value = null
    }

    /**
     * Copy a recorded request to the clipboard as a runnable `curl` command.
     *
     * Cookies are taken from the entry when it has them and from the WebView's cookie store
     * otherwise. That fallback is what makes an exported POST actually work: those entries come
     * from the page agent, and neither fetch nor XHR will reveal the `Cookie` header to a script.
     *
     * The command is never redacted, because a redacted request does not run. The returned result
     * says so, so the panel can warn that the clipboard now holds a live credential.
     */
    fun copyAsCurl(context: Context, entry: RecordedEntry) {
        val curl = CurlBuilder.build(
            entry = entry,
            cookieHeader = runCatching {
                CookieManager.getInstance().getCookie(entry.url)
            }.getOrNull()
        )

        val copied = runCatching {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("curl", curl))
        }.isSuccess

        _lastExport.value = if (copied) {
            ExportResult.CurlCopied(entry.url)
        } else {
            logger.log(TAG, "Could not write the curl command to the clipboard")
            ExportResult.Failed
        }
    }

    /** Send the `curl` command to another app, for when the clipboard is not the destination. */
    fun shareCurl(context: Context, entry: RecordedEntry) {
        val curl = CurlBuilder.build(
            entry = entry,
            cookieHeader = runCatching {
                CookieManager.getInstance().getCookie(entry.url)
            }.getOrNull()
        )

        runCatching {
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, curl)
                        putExtra(Intent.EXTRA_SUBJECT, "curl ${entry.url}")
                    },
                    "Share curl command"
                )
            )
        }.onFailure {
            logger.log(TAG, "No app available to receive the curl command", it)
            _lastExport.value = ExportResult.Failed
        }
    }

    /**
     * Write the recorded session to a `.har` file in Downloads.
     *
     * Note the reload caveat surfaced to the user: entries are only captured while recording is
     * on, so a session started mid-page will not contain the initial document request.
     */
    fun exportHar(context: Context) {
        val entries = networkRecorder.entries.value
        if (entries.isEmpty()) {
            _lastExport.value = ExportResult.Empty
            return
        }

        val archive = harExporter.buildArchive(
            pages = networkRecorder.pages.value,
            entries = entries,
            redactSecrets = _redactSecrets.value
        )
        val fileName = harExporter.suggestFileName(networkRecorder.pages.value)
        val contents = harExporter.toJson(archive)

        val uri = harExporter.writeToDownloads(context, fileName, contents)
        _lastExport.value = if (uri != null) {
            ExportResult.Saved(fileName, entries.size, uri)
        } else {
            logger.log(TAG, "Could not write $fileName to Downloads")
            ExportResult.Failed
        }
    }

    /** Send the recorded session to another app without saving it to Downloads first. */
    fun shareHar(context: Context) {
        val entries = networkRecorder.entries.value
        if (entries.isEmpty()) {
            _lastExport.value = ExportResult.Empty
            return
        }

        val archive = harExporter.buildArchive(
            pages = networkRecorder.pages.value,
            entries = entries,
            redactSecrets = _redactSecrets.value
        )
        val fileName = harExporter.suggestFileName(networkRecorder.pages.value)
        val uri = harExporter.writeForSharing(context, fileName, harExporter.toJson(archive))

        if (uri == null) {
            _lastExport.value = ExportResult.Failed
            return
        }

        runCatching {
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, fileName)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    "Share HAR"
                )
            )
        }.onFailure {
            logger.log(TAG, "No app available to receive the HAR", it)
            _lastExport.value = ExportResult.Failed
        }
    }

    /** The outcome of an export, surfaced to the panel so it can confirm what happened. */
    sealed interface ExportResult {
        data class Saved(val fileName: String, val entryCount: Int, val uri: Uri) : ExportResult
        data class CurlCopied(val url: String) : ExportResult
        data object Empty : ExportResult
        data object Failed : ExportResult
    }

    private companion object {
        const val TAG = "DevToolsController"
    }
}

/** Levels that should be rendered in an alerting colour. */
fun ConsoleMessage.isProblem(): Boolean =
    level == ConsoleMessage.Level.ERROR || level == ConsoleMessage.Level.WARN
