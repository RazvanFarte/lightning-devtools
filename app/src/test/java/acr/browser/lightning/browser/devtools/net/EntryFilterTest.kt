package acr.browser.lightning.browser.devtools.net

import acr.browser.lightning.browser.devtools.har.HarTimings
import org.junit.Assert.assertEquals
import org.junit.Test

class EntryFilterTest {

    private fun entry(url: String, method: String = "GET", status: Int = 200, mime: String = "") =
        RecordedEntry(
            id = url,
            pageRef = "page_0",
            startedWallClockMillis = 0,
            method = method,
            url = url,
            protocol = "",
            status = status,
            mimeType = mime,
            source = RecordedEntry.Source.NATIVE,
            timings = HarTimings(send = 0.0, wait = 0.0, receive = 0.0)
        )

    private val entries = listOf(
        entry("https://domain.com/api/v1/users"),
        entry("https://domain.com/graphql", method = "POST", mime = "application/json"),
        entry("https://analytics.example.net/collect?v=1", status = 204),
        entry("https://cdn.example.net/app.js", mime = "text/javascript")
    )

    private fun urls(query: String) = EntryFilter.apply(entries, query).map { it.url }

    @Test
    fun blankQueryKeepsEverything() {
        assertEquals(entries.size, EntryFilter.apply(entries, "   ").size)
    }

    @Test
    fun matchesSubstringIgnoringCase() {
        assertEquals(listOf("https://domain.com/graphql"), urls("GraphQL"))
    }

    @Test
    fun allTermsMustMatch() {
        assertEquals(listOf("https://domain.com/api/v1/users"), urls("domain.com api"))
    }

    @Test
    fun matchesMethodStatusAndMimeType() {
        assertEquals(1, urls("post").size)
        assertEquals(listOf("https://analytics.example.net/collect?v=1"), urls("204"))
        assertEquals(listOf("https://cdn.example.net/app.js"), urls("javascript"))
    }

    @Test
    fun noMatchGivesEmptyList() {
        assertEquals(emptyList<String>(), urls("nonexistent"))
    }
}
