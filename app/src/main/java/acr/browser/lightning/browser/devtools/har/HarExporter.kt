package acr.browser.lightning.browser.devtools.har

import acr.browser.lightning.BuildConfig
import acr.browser.lightning.browser.devtools.net.RecordedEntry
import acr.browser.lightning.browser.devtools.net.RecordedPage
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns recorded traffic into a HAR 1.2 file on the device.
 *
 * Everything here targets *on-device* use: the file lands in the public Downloads collection so a
 * file manager can see it, and a share URI is offered so it can be sent onward without a cable.
 * That is the whole point of the feature — the standard HAR workflow assumes a desktop browser
 * attached over USB, which is exactly what is unavailable here.
 */
@Singleton
class HarExporter @Inject constructor() {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        // HAR readers reject unknown-but-absent optionals far less often than nulls, and omitting
        // nulls keeps the file readable and considerably smaller.
        explicitNulls = false
    }

    /** Assemble the archive. Entries are ordered by start time so the waterfall reads correctly. */
    fun buildArchive(
        pages: List<RecordedPage>,
        entries: List<RecordedEntry>,
        browserVersion: String = BuildConfig.VERSION_NAME
    ): HarArchive = HarArchive(
        log = HarLog(
            version = "1.2",
            creator = HarCreator(name = CREATOR_NAME, version = BuildConfig.VERSION_NAME),
            browser = HarCreator(name = "Android WebView", version = browserVersion),
            pages = pages.map { it.toHarPage() },
            entries = entries.sortedBy { it.startedWallClockMillis }.map { it.toHarEntry() }
        )
    )

    fun toJson(archive: HarArchive): String = json.encodeToString(archive)

    /** A filename that sorts chronologically and is safe on every filesystem. */
    fun suggestFileName(pages: List<RecordedPage>): String {
        val host = pages.lastOrNull()?.url
            ?.let { runCatching { URI(it).host }.getOrNull() }
            ?.replace(Regex("[^A-Za-z0-9.-]"), "_")
            ?: "session"
        val stamp = FILE_STAMP.format(Instant.now().atZone(ZoneId.systemDefault()))
        return "$host-$stamp.har"
    }

    /**
     * Write the archive into the device's Downloads folder.
     *
     * On API 29+ this goes through MediaStore, which needs no runtime permission and makes the
     * file visible to every file manager. Below that, scoped storage does not exist yet and
     * writing to shared Downloads would require WRITE_EXTERNAL_STORAGE, so the app's own external
     * directory is used instead and the caller is expected to offer sharing.
     */
    fun writeToDownloads(context: Context, fileName: String, contents: String): Uri? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, HAR_MIME_TYPE)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching null

            resolver.openOutputStream(uri)?.use { it.write(contents.toByteArray()) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } else {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: return@runCatching null
            val file = File(dir, fileName).apply { writeText(contents) }
            fileProviderUri(context, file)
        }
    }.getOrNull()

    /**
     * Write to the app cache and return a shareable URI, for sending the archive straight to
     * another app rather than saving it.
     */
    fun writeForSharing(context: Context, fileName: String, contents: String): Uri? = runCatching {
        val dir = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
        val file = File(dir, fileName).apply { writeText(contents) }
        fileProviderUri(context, file)
    }.getOrNull()

    private fun fileProviderUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    private fun RecordedPage.toHarPage() = HarPage(
        startedDateTime = startedWallClockMillis.toIso8601(),
        id = ref,
        title = title.ifBlank { url },
        pageTimings = HarPageTimings(
            onContentLoad = onContentLoadMillis,
            onLoad = onLoadMillis
        )
    )

    private fun RecordedEntry.toHarEntry(): HarEntry {
        val requestCookies = requestHeaders
            .firstOrNull { it.name.equals("Cookie", ignoreCase = true) }
            ?.value
            ?.split(';')
            ?.mapNotNull { pair ->
                val name = pair.substringBefore('=').trim()
                name.takeIf { it.isNotEmpty() }
                    ?.let { HarCookie(name = it, value = pair.substringAfter('=', "").trim()) }
            }
            .orEmpty()

        val responseCookies = responseHeaders
            .filter { it.name.equals("Set-Cookie", ignoreCase = true) }
            .mapNotNull { it.value.toHarCookie() }

        return HarEntry(
            pageref = pageRef,
            startedDateTime = startedWallClockMillis.toIso8601(),
            time = totalTimeMillis,
            request = HarRequest(
                method = method,
                url = url,
                httpVersion = protocol.ifBlank { "unknown" },
                cookies = requestCookies,
                headers = requestHeaders,
                queryString = url.parseQueryString(),
                postData = requestBody?.let {
                    HarPostData(
                        mimeType = requestBodyMimeType ?: "application/octet-stream",
                        text = it
                    )
                },
                headersSize = -1,
                bodySize = requestBody?.toByteArray()?.size?.toLong() ?: 0
            ),
            response = HarResponse(
                status = status,
                statusText = statusText,
                httpVersion = protocol.ifBlank { "unknown" },
                cookies = responseCookies,
                headers = responseHeaders,
                content = HarContent(
                    size = decodedBodySize.coerceAtLeast(0),
                    // A negative or absent transfer size means we cannot state a saving, and the
                    // spec prefers the field omitted over a fabricated zero.
                    compression = (decodedBodySize - transferBodySize)
                        .takeIf { transferBodySize >= 0 && it > 0 },
                    mimeType = mimeType,
                    text = bodyText,
                    encoding = bodyEncoding,
                    comment = "Body truncated at capture limit".takeIf { bodyTruncated }
                ),
                redirectURL = responseHeaders
                    .firstOrNull { it.name.equals("Location", ignoreCase = true) }
                    ?.value
                    .orEmpty(),
                headersSize = -1,
                bodySize = transferBodySize
            ),
            timings = timings,
            serverIPAddress = serverIpAddress,
            resourceType = resourceType,
            source = source.name.lowercase(),
            fromCache = "memory".takeIf { fromCache },
            comment = error?.let { "Request failed: $it" }
        )
    }

    private companion object {
        const val CREATOR_NAME = "Lightning DevTools"
        const val HAR_MIME_TYPE = "application/json"
        const val EXPORT_DIR = "devtools"

        val FILE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        /**
         * HAR requires ISO 8601 with milliseconds and an explicit offset, e.g.
         * `2009-04-16T12:07:23.596+02:00`. Viewers show the raw string, so the local offset is
         * kept rather than normalising to UTC.
         */
        val ISO_8601: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")

        fun Long.toIso8601(): String =
            ISO_8601.format(Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()))

        /** Split the query out of a URL into HAR's name/value form. */
        fun String.parseQueryString(): List<HarQueryParam> =
            substringAfter('?', "")
                .substringBefore('#')
                .split('&')
                .filter { it.isNotBlank() }
                .map {
                    HarQueryParam(
                        name = it.substringBefore('='),
                        value = it.substringAfter('=', "")
                    )
                }

        /** Parse a Set-Cookie header value into the HAR cookie form. */
        fun String.toHarCookie(): HarCookie? {
            val parts = split(';')
            val first = parts.firstOrNull()?.trim() ?: return null
            val name = first.substringBefore('=').trim().ifEmpty { return null }
            val attributes = parts.drop(1).map { it.trim() }

            return HarCookie(
                name = name,
                value = first.substringAfter('=', "").trim(),
                path = attributes.firstOrNull { it.startsWith("Path=", true) }?.substringAfter('='),
                domain = attributes.firstOrNull { it.startsWith("Domain=", true) }
                    ?.substringAfter('='),
                expires = attributes.firstOrNull { it.startsWith("Expires=", true) }
                    ?.substringAfter('='),
                httpOnly = attributes.any { it.equals("HttpOnly", ignoreCase = true) },
                secure = attributes.any { it.equals("Secure", ignoreCase = true) }
            )
        }
    }
}
