package acr.browser.lightning.useragent

import acr.browser.lightning.constant.DESKTOP_USER_AGENT

private val CHROME_MAJOR_VERSION = Regex("""Chrome/(\d+)""")

/**
 * Build the user agent a "Request desktop site" tab should send, derived from the WebView's own
 * mobile user agent the same way Chrome for Android does it: a Linux desktop platform and the
 * reduced `Chrome/<major>.0.0.0` version token, so the site sees the engine version it is really
 * talking to rather than a stale hardcoded one. Falls back to [DESKTOP_USER_AGENT] if [mobileUserAgent]
 * carries no Chrome version.
 */
fun desktopUserAgentFrom(mobileUserAgent: String): String {
    val majorVersion = CHROME_MAJOR_VERSION.find(mobileUserAgent)?.groupValues?.get(1)
        ?: return DESKTOP_USER_AGENT
    return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/$majorVersion.0.0.0 Safari/537.36"
}

/**
 * The layout width a desktop-mode page is given. Chrome uses 980 for pages that don't declare a
 * viewport; most desktop layouts start breaking just under 1024, so this errs slightly wider.
 */
const val DESKTOP_VIEWPORT_WIDTH = 1024

/**
 * Injected at document start while a tab is in desktop mode. A user agent alone is not enough:
 * responsive sites ship `<meta name="viewport" content="width=device-width">` and keep rendering
 * their phone layout whatever the user agent says, which is exactly the case a user reaches for
 * "Request desktop site" to get out of. Rewriting every viewport tag, including ones a script
 * inserts later, gives the page a desktop-width layout viewport, as Chrome does.
 */
val DESKTOP_VIEWPORT_JS = """
(function () {
  var content = 'width=$DESKTOP_VIEWPORT_WIDTH';
  function rewrite(meta) {
    if (meta.tagName === 'META' && meta.name && meta.name.toLowerCase() === 'viewport' &&
        meta.content !== content) {
      meta.content = content;
    }
  }
  function rewriteAll() {
    var metas = document.getElementsByTagName('meta');
    for (var i = 0; i < metas.length; i++) rewrite(metas[i]);
  }
  new MutationObserver(function (mutations) {
    for (var i = 0; i < mutations.length; i++) {
      var m = mutations[i];
      if (m.type === 'attributes') { rewrite(m.target); continue; }
      for (var j = 0; j < m.addedNodes.length; j++) {
        var node = m.addedNodes[j];
        rewrite(node);
        if (node.getElementsByTagName) {
          var nested = node.getElementsByTagName('meta');
          for (var k = 0; k < nested.length; k++) rewrite(nested[k]);
        }
      }
    }
  }).observe(document, {
    childList: true,
    subtree: true,
    attributes: true,
    attributeFilter: ['content', 'name']
  });
  rewriteAll();
})();
""".trimIndent()
