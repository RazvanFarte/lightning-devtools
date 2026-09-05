package acr.browser.lightning.browser.devtools.console

import acr.browser.lightning.di.BrowserScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/** A console message captured from a page. */
data class ConsoleMessage(
    val level: Level,
    val text: String,
    val timestampMillis: Long
) {
    enum class Level {
        LOG, INFO, WARN, ERROR, DEBUG, INPUT, RESULT;

        companion object {
            fun from(value: String?): Level =
                entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: LOG
        }
    }
}

/**
 * Holds console output for the current browser session.
 *
 * Lives in native code rather than in the page, so the log survives navigation and reload — one
 * of the more irritating limitations of extension-based mobile inspectors, where the panel is
 * torn down with the document that hosts it.
 */
@BrowserScope
class ConsoleLog @Inject constructor() {

    private val _messages = MutableStateFlow<List<ConsoleMessage>>(emptyList())
    val messages: StateFlow<List<ConsoleMessage>> = _messages.asStateFlow()

    fun add(message: ConsoleMessage) {
        _messages.update { current ->
            if (current.size >= MAX_MESSAGES) {
                current.drop(current.size - MAX_MESSAGES + 1) + message
            } else {
                current + message
            }
        }
    }

    fun clear() {
        _messages.value = emptyList()
    }

    private companion object {
        const val MAX_MESSAGES = 2000
    }
}
