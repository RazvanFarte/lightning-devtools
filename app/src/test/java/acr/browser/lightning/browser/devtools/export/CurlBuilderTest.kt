package acr.browser.lightning.browser.devtools.export

import acr.browser.lightning.browser.devtools.har.HarHeader
import acr.browser.lightning.browser.devtools.har.HarTimings
import acr.browser.lightning.browser.devtools.net.RecordedEntry
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class CurlBuilderTest {

    @Test
    fun `a plain GET needs no explicit method`() {
        val curl = CurlBuilder.build(entry(), multiline = false)

        assertThat(curl).startsWith("curl 'https://example.com/api/user/login/'")
        assertThat(curl).doesNotContain("-X GET")
        assertThat(curl).contains("--compressed")
    }

    @Test
    fun `a body implies POST so no method flag is emitted`() {
        val curl = CurlBuilder.build(
            entry(method = "POST", body = """{"email":"a@b.c"}"""),
            multiline = false
        )

        assertThat(curl).doesNotContain("-X POST")
        assertThat(curl).contains("""--data-raw '{"email":"a@b.c"}'""")
    }

    @Test
    fun `an unusual method is stated explicitly`() {
        val curl = CurlBuilder.build(entry(method = "DELETE"), multiline = false)

        assertThat(curl).contains("-X DELETE")
    }

    @Test
    fun `cookies fall back to the store when the entry has none`() {
        val curl = CurlBuilder.build(entry(), cookieHeader = "session=abc123", multiline = false)

        assertThat(curl).contains("-H 'Cookie: session=abc123'")
    }

    @Test
    fun `a recorded cookie header wins over the store`() {
        val curl = CurlBuilder.build(
            entry(headers = listOf(HarHeader("Cookie", "recorded=1"))),
            cookieHeader = "fromstore=2",
            multiline = false
        )

        assertThat(curl).contains("recorded=1")
        assertThat(curl).doesNotContain("fromstore=2")
    }

    @Test
    fun `headers curl recomputes are dropped`() {
        val curl = CurlBuilder.build(
            entry(
                headers = listOf(
                    HarHeader("Content-Length", "42"),
                    HarHeader("Host", "example.com"),
                    HarHeader("Accept-Encoding", "gzip"),
                    HarHeader(":authority", "example.com"),
                    HarHeader("Authorization", "Bearer xyz")
                )
            ),
            multiline = false
        )

        assertThat(curl).doesNotContain("Content-Length")
        assertThat(curl).doesNotContain("Host:")
        assertThat(curl).doesNotContain("Accept-Encoding")
        assertThat(curl).doesNotContain(":authority")
        // Auth must survive, otherwise the exported command cannot run.
        assertThat(curl).contains("-H 'Authorization: Bearer xyz'")
    }

    @Test
    fun `single quotes in a body are escaped so the shell keeps them literal`() {
        val curl = CurlBuilder.build(
            entry(method = "POST", body = """{"name":"O'Brien"}"""),
            multiline = false
        )

        // The POSIX idiom: close the quote, emit an escaped one, reopen.
        assertThat(curl).contains("""--data-raw '{"name":"O'\''Brien"}'""")
    }

    @Test
    fun `multiline output puts each flag on a continued line`() {
        val curl = CurlBuilder.build(entry(headers = listOf(HarHeader("Accept", "*/*"))))

        assertThat(curl).contains(" \\\n  ")
        assertThat(curl.lines().first()).isEqualTo("curl 'https://example.com/api/user/login/' \\")
    }

    private fun entry(
        method: String = "GET",
        headers: List<HarHeader> = emptyList(),
        body: String? = null
    ) = RecordedEntry(
        id = "1",
        pageRef = "page_1",
        startedWallClockMillis = 0,
        method = method,
        url = "https://example.com/api/user/login/",
        protocol = "h2",
        requestHeaders = headers,
        requestBody = body,
        timings = HarTimings(send = 0.0, wait = 0.0, receive = 0.0),
        source = RecordedEntry.Source.NATIVE
    )
}
