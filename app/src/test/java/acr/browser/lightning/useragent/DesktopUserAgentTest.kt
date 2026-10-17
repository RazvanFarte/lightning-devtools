package acr.browser.lightning.useragent

import acr.browser.lightning.constant.DESKTOP_USER_AGENT
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class DesktopUserAgentTest {

    @Test
    fun `desktop agent keeps the WebView's real Chrome major version`() {
        val webViewAgent = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/AP2A.240805.005; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/141.0.7390.122 " +
            "Mobile Safari/537.36"

        assertThat(desktopUserAgentFrom(webViewAgent)).isEqualTo(
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/141.0.0.0 Safari/537.36"
        )
    }

    @Test
    fun `desktop agent carries no mobile, Android or WebView markers`() {
        val agent = desktopUserAgentFrom(
            "Mozilla/5.0 (Linux; Android 10; K; wv) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Version/4.0 Chrome/120.0.0.0 Mobile Safari/537.36"
        )

        assertThat(agent).doesNotContain("Mobile", "Android", "wv", "Version/4.0")
    }

    @Test
    fun `an agent without a Chrome version falls back to the hardcoded desktop agent`() {
        assertThat(desktopUserAgentFrom("SomeCustomAgent/1.0")).isEqualTo(DESKTOP_USER_AGENT)
    }
}
