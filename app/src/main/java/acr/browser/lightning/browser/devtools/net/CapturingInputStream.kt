package acr.browser.lightning.browser.devtools.net

import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream

/**
 * Passes a response body through to the WebView while copying up to [captureLimitBytes] of it
 * aside for the recorder.
 *
 * The body is teed rather than buffered up front for two reasons: the WebView receives its first
 * byte with no added latency, and a large media file never has to fit in memory. [onComplete] is
 * invoked exactly once, when the stream reaches its end or is closed, which is also the moment
 * OkHttp reports `responseBodyEnd` — so the entry is finalised with a correct `receive` timing.
 *
 * @param shouldCapture when false the bytes are still counted but never retained, which is how
 *   video and other large binary bodies are handled.
 * @param onComplete receives the captured bytes (null if nothing was retained), whether the
 *   capture was cut short by the limit, and the total number of bytes that passed through.
 */
class CapturingInputStream(
    delegate: InputStream,
    private val captureLimitBytes: Int,
    private val shouldCapture: Boolean,
    private val onComplete: (captured: ByteArray?, truncated: Boolean, totalBytes: Long) -> Unit
) : FilterInputStream(delegate) {

    private val buffer = ByteArrayOutputStream()
    private var totalBytes = 0L
    private var truncated = false
    private var completed = false

    override fun read(): Int {
        val value = super.read()
        if (value == -1) {
            complete()
        } else {
            totalBytes++
            if (shouldCapture && buffer.size() < captureLimitBytes) {
                buffer.write(value)
            } else if (shouldCapture) {
                truncated = true
            }
        }
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val count = super.read(b, off, len)
        if (count == -1) {
            complete()
        } else {
            totalBytes += count
            if (shouldCapture) {
                val remaining = captureLimitBytes - buffer.size()
                if (remaining > 0) {
                    val toWrite = minOf(remaining, count)
                    buffer.write(b, off, toWrite)
                    if (toWrite < count) truncated = true
                } else {
                    truncated = true
                }
            }
        }
        return count
    }

    override fun close() {
        try {
            super.close()
        } finally {
            // A stream abandoned before EOF still has to produce an entry, otherwise a request the
            // WebView cancelled part way through would silently vanish from the log.
            complete()
        }
    }

    private fun complete() {
        if (completed) return
        completed = true
        onComplete(
            if (shouldCapture) buffer.toByteArray() else null,
            truncated,
            totalBytes
        )
    }
}
