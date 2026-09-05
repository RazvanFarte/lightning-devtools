package acr.browser.lightning.browser.devtools.har

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * Regression tests for credential scrubbing.
 *
 * These exist because a real exported archive was found to contain a login password in cleartext,
 * so the cases below are taken from the shape of that file.
 */
class HarRedactorTest {

    @Test
    fun `json login body has its password removed but keeps the other fields`() {
        val archive = archiveWith(
            postData = HarPostData(
                mimeType = "application/json",
                text = """{"email":"user@example.com","password":"hunter2"}"""
            )
        )

        val text = HarRedactor.redact(archive).log.entries.first().request.postData!!.text

        assertThat(text).doesNotContain("hunter2")
        assertThat(text).contains(HarRedactor.PLACEHOLDER)
        // The non-secret field must survive, otherwise the archive is useless for debugging.
        assertThat(text).contains("user@example.com")
    }

    @Test
    fun `nested and camel case secrets are found too`() {
        val archive = archiveWith(
            postData = HarPostData(
                mimeType = "application/json",
                text = """{"user":{"newPassword":"hunter2","name":"ada"},"access_token":"abc123"}"""
            )
        )

        val text = HarRedactor.redact(archive).log.entries.first().request.postData!!.text

        assertThat(text).doesNotContain("hunter2")
        assertThat(text).doesNotContain("abc123")
        assertThat(text).contains("ada")
    }

    @Test
    fun `form encoded bodies are scrubbed per field`() {
        val archive = archiveWith(
            postData = HarPostData(
                mimeType = "application/x-www-form-urlencoded",
                text = "email=user%40example.com&password=hunter2&remember=1"
            )
        )

        val text = HarRedactor.redact(archive).log.entries.first().request.postData!!.text

        assertThat(text).doesNotContain("hunter2")
        assertThat(text).contains("email=user%40example.com")
        assertThat(text).contains("remember=1")
    }

    @Test
    fun `a body of unknown type is dropped rather than guessed at`() {
        val archive = archiveWith(
            postData = HarPostData(mimeType = "application/octet-stream", text = "hunter2")
        )

        val text = HarRedactor.redact(archive).log.entries.first().request.postData!!.text

        assertThat(text).doesNotContain("hunter2")
        assertThat(text).contains(HarRedactor.PLACEHOLDER)
    }

    @Test
    fun `authorization and cookie headers are replaced on both sides`() {
        val archive = archiveWith(
            requestHeaders = listOf(
                HarHeader("Authorization", "Bearer secret-token"),
                HarHeader("Cookie", "session=abc123"),
                HarHeader("Accept", "application/json")
            ),
            responseHeaders = listOf(HarHeader("Set-Cookie", "session=abc123; HttpOnly"))
        )

        val entry = HarRedactor.redact(archive).log.entries.first()

        assertThat(entry.request.headers.map { it.value })
            .containsExactly(HarRedactor.PLACEHOLDER, HarRedactor.PLACEHOLDER, "application/json")
        assertThat(entry.response.headers.single().value).isEqualTo(HarRedactor.PLACEHOLDER)
    }

    @Test
    fun `tokens in the query string are replaced`() {
        val archive = archiveWith(
            queryString = listOf(
                HarQueryParam("access_token", "abc123"),
                HarQueryParam("page", "2")
            )
        )

        val params = HarRedactor.redact(archive).log.entries.first().request.queryString

        assertThat(params.first { it.name == "access_token" }.value)
            .isEqualTo(HarRedactor.PLACEHOLDER)
        assertThat(params.first { it.name == "page" }.value).isEqualTo("2")
    }

    @Test
    fun `redaction leaves an archive without secrets untouched`() {
        val archive = archiveWith(
            requestHeaders = listOf(HarHeader("Accept", "text/html")),
            queryString = listOf(HarQueryParam("page", "2"))
        )

        assertThat(HarRedactor.redact(archive)).isEqualTo(archive)
    }

    private fun archiveWith(
        postData: HarPostData? = null,
        requestHeaders: List<HarHeader> = emptyList(),
        responseHeaders: List<HarHeader> = emptyList(),
        queryString: List<HarQueryParam> = emptyList()
    ) = HarArchive(
        log = HarLog(
            creator = HarCreator("test", "1"),
            entries = listOf(
                HarEntry(
                    startedDateTime = "2026-09-05T23:30:00.000+03:00",
                    time = 1.0,
                    request = HarRequest(
                        method = if (postData == null) "GET" else "POST",
                        url = "https://example.com/api/user/login/",
                        httpVersion = "h2",
                        headers = requestHeaders,
                        queryString = queryString,
                        postData = postData
                    ),
                    response = HarResponse(
                        status = 200,
                        statusText = "OK",
                        httpVersion = "h2",
                        headers = responseHeaders,
                        content = HarContent(size = 0)
                    ),
                    timings = HarTimings(send = 0.0, wait = 1.0, receive = 0.0)
                )
            )
        )
    )
}
