package acr.browser.lightning.browser.devtools.net

/**
 * Free-text filter for the network list.
 *
 * The query is split on whitespace and every term must match somewhere in the entry's URL, method,
 * status code or MIME type, ignoring case — so `api graphql` narrows to requests that mention both,
 * and `domain.com POST` finds posts to that host. A blank query matches everything.
 */
object EntryFilter {

    fun matches(entry: RecordedEntry, query: String): Boolean {
        val terms = query.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return true

        val haystack = "${entry.url} ${entry.method} ${entry.status} ${entry.mimeType}"
        return terms.all { haystack.contains(it, ignoreCase = true) }
    }

    fun apply(entries: List<RecordedEntry>, query: String): List<RecordedEntry> =
        if (query.isBlank()) entries else entries.filter { matches(it, query) }

    private val WHITESPACE = Regex("\\s+")
}
