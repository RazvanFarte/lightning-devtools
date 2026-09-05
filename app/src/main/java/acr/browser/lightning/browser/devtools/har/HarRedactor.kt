package acr.browser.lightning.browser.devtools.har

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Strips credentials out of an archive before it leaves the device.
 *
 * A HAR of a real session contains whatever was typed into a login form, verbatim: the first
 * export from this browser captured a password in cleartext. Since the whole point of the feature
 * is to produce a file you then send somewhere, redaction is the default and has to be turned off
 * deliberately.
 *
 * Redaction happens at export, not at capture, so the live panel still shows real values while
 * you are debugging.
 */
object HarRedactor {

    const val PLACEHOLDER = "[redacted]"

    /** Header names whose entire value is a credential. */
    private val SENSITIVE_HEADERS = setOf(
        "authorization",
        "proxy-authorization",
        "cookie",
        "set-cookie",
        "x-api-key",
        "x-auth-token",
        "x-csrf-token",
        "x-xsrf-token"
    )

    /**
     * Body and query field names that hold credentials.
     *
     * Matched as substrings so that `user_password`, `newPassword` and `access_token` are all
     * caught, at the cost of occasionally redacting something harmless — the safe direction.
     */
    private val SENSITIVE_FIELDS = listOf(
        "password", "passwd", "pwd", "secret", "token", "apikey", "api_key",
        "auth", "credential", "otp", "passcode", "session"
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun redact(archive: HarArchive): HarArchive =
        archive.copy(log = archive.log.copy(entries = archive.log.entries.map(::redactEntry)))

    private fun redactEntry(entry: HarEntry): HarEntry = entry.copy(
        request = entry.request.copy(
            headers = entry.request.headers.map(::redactHeader),
            cookies = entry.request.cookies.map { it.copy(value = PLACEHOLDER) },
            queryString = entry.request.queryString.map(::redactQueryParam),
            postData = entry.request.postData?.let(::redactPostData)
        ),
        response = entry.response.copy(
            headers = entry.response.headers.map(::redactHeader),
            cookies = entry.response.cookies.map { it.copy(value = PLACEHOLDER) }
        )
    )

    private fun redactHeader(header: HarHeader): HarHeader =
        if (header.name.lowercase() in SENSITIVE_HEADERS) {
            header.copy(value = PLACEHOLDER)
        } else {
            header
        }

    private fun redactQueryParam(param: HarQueryParam): HarQueryParam =
        if (isSensitiveName(param.name)) param.copy(value = PLACEHOLDER) else param

    private fun redactPostData(postData: HarPostData): HarPostData {
        val mime = postData.mimeType.lowercase()
        val text = when {
            postData.text.isBlank() -> postData.text
            mime.contains("json") -> redactJson(postData.text)
            mime.contains("x-www-form-urlencoded") -> redactForm(postData.text)
            // An unrecognised type could be anything, so the body is dropped wholesale rather
            // than guessed at. Its length is kept, which is usually what you need anyway.
            else -> "$PLACEHOLDER (${postData.text.length} chars)"
        }

        return postData.copy(
            text = text,
            params = postData.params.map {
                if (isSensitiveName(it.name)) it.copy(value = PLACEHOLDER) else it
            }
        )
    }

    private fun redactJson(text: String): String = runCatching {
        json.encodeToString(JsonElement.serializer(), redactElement(json.parseToJsonElement(text)))
    }.getOrElse { "$PLACEHOLDER (${text.length} chars)" }

    private fun redactElement(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> buildJsonObject {
            element.forEach { (key, value) ->
                put(
                    key,
                    if (isSensitiveName(key)) JsonPrimitive(PLACEHOLDER) else redactElement(value)
                )
            }
        }

        is JsonArray -> JsonArray(element.map(::redactElement))
        else -> element
    }

    private fun redactForm(text: String): String = text.split('&').joinToString("&") { pair ->
        val name = pair.substringBefore('=')
        if (isSensitiveName(name)) "$name=$PLACEHOLDER" else pair
    }

    private fun isSensitiveName(name: String): Boolean {
        val lower = name.lowercase()
        return SENSITIVE_FIELDS.any { lower.contains(it) }
    }
}
