# Progress log

A release-by-release account of what was built, what broke, and what fixed it. Ordered oldest
first so the reasoning reads as it actually happened — several releases exist specifically
*because* a real exported HAR or a real install showed something the previous one got wrong.

## v0.1.0 — HAR model, recorder, panel

The starting shape of the feature, built on a fork of
[Lightning-Browser](https://github.com/anthonycr/Lightning-Browser) chosen because it already
depended on OkHttp, kotlinx.serialization and Compose/Material3 — exactly what a HAR recorder
needs — and had a `shouldInterceptRequest` hook already in place.

- `har/Har.kt` — a HAR 1.2 model kept spec-exact so the output loads unmodified in Chrome DevTools,
  Firefox and Charles.
- `net/NetworkRecorder.kt` — replays intercepted WebView requests through OkHttp, the only way to
  obtain status, response headers, bodies and timings from native code. Opt-in, so ordinary
  browsing is untouched while recording is off.
- `net/TimingEventListener.kt` — maps OkHttp's call-phase callbacks onto HAR's `timings` object.
- `js/DevToolsAgent.js` + `bridge/DevToolsBridge.kt` — a page agent injected at document start,
  reporting over `addWebMessageListener` rather than `addJavascriptInterface` (which would expose a
  Java object to every frame on the page). Supplies what native interception structurally cannot:
  request bodies, console output, page lifecycle.
- `compose/DevToolsPanel.kt` — a native Compose UI, not a second WebView. Chosen because a
  DOM-based mobile inspector (the reference point, Firefox's MobiDevTools add-on) has to
  reimplement scrolling by hand, and its worst reviews were exactly that: scroll gestures
  misread as taps, and the log wiped itself on reload because it lived inside the page it was
  inspecting.

Verified: compiles, packages, and the devtools classes and the agent string are present in the
built APK's dex. Not yet exercised on a device.

## v0.1.1 — the first real session found two breaking bugs

The first export, from a real login attempt on app.usebraintrust.com, immediately surfaced two
defects and one privacy problem:

- **Login was impossible while recording.** Every non-GET request was replayed with
  `ByteArray(0)` as its body — the comment above the code claimed such requests were "skipped";
  they were not. `shouldInterceptRequest` never exposes a request body and no API recovers it, so
  the fix was to stop intercepting body-bearing methods at all and let the page agent, which sees
  the real payload before it is sent, capture them instead.
- **Request headers were essentially absent.** Agent entries had none, because the JS agent never
  collected them. Native entries never had `Cookie`, because `WebResourceRequest.requestHeaders`
  silently omits it. Fixed by hooking `XMLHttpRequest.setRequestHeader` and reading `fetch`'s
  `init.headers`/`Request.headers` on the agent side, and by recording what OkHttp actually sent
  on the native side.
- **A live password was in the exported file.** `HarRedactor` was added and made the default:
  passwords, tokens, `Authorization` and `Cookie` are stripped from every export unless the user
  explicitly turns redaction off, because the alternative is a debugging file that is also a
  credential leak. Nested and camelCase field names (`user.newPassword`, `access_token`) are
  matched too. 7 unit tests.

A duplicate-entry problem was fixed at the same time: every bodyless XHR had been logged twice,
once per capture layer, inflating an 11 MB file with pure redundancy. Agent and native reports for
the same exchange are now merged by method, URL and a time window.

## v0.1.2 — copy a request as curl

Requested directly: long-press a network row, or its `⋮` button, to get a runnable `curl` command.

The one design decision that mattered: **cookies cannot come from the recorded data alone.**
`Cookie` is a forbidden header name in the fetch and XHR specs, and HttpOnly cookies are invisible
to scripts by design — so every agent-captured POST (which, after v0.1.1, is most of them) has no
cookie to copy. `CurlBuilder` falls back to Android's `CookieManager` for the URL, preferring a
recorded header when one genuinely exists. This was later confirmed end-to-end: a copied `curl` for
`GET /api/employers/8699/` ran successfully outside the app and returned an authenticated 200,
proving the HttpOnly `sessionid` really did make it into the command.

The command is deliberately **not** redacted, even though HAR export is by default: a request
stripped of its cookies does not run, so redacting it would defeat the feature. The confirmation
message says plainly that the clipboard now holds a live credential. 8 unit tests.

## v0.1.3 — a second real archive found three more gaps

A 295-entry export split cleanly: the 245 native entries were essentially complete, the 50
agent-captured POSTs were not.

- **Redirects were logged as failures.** `WebResourceResponse` throws on any 3xx status
  ("statusCode can't be in the [300, 399] range"), so every redirect hop had been recorded as an
  error with no headers. Fixed by recording the hop properly and then declining to return it,
  letting the WebView follow the redirect itself — at the cost of fetching that one hop twice,
  which is an acceptable trade for a debugging tool.
- **Non-replayable requests had no request headers at all.** Because POSTs are never replayed,
  nothing native had been recorded for them; the agent alone cannot see `Content-Type`, `Origin`,
  `Referer` it didn't set itself, or `Cookie` under any circumstances. A metadata-only native entry
  is now recorded for every declined request, carrying the real headers plus the cookie pulled from
  the store, and the agent's later report merges into it.
- **28 of 50 POST bodies were empty.** The agent only handled string bodies; Google Analytics and
  DoubleClick send `Blob`, `URLSearchParams` and typed arrays. All are now handled, `Blob`
  asynchronously since reading one is inherently async.
- **`navigator.sendBeacon` was invisible.** Analytics libraries prefer it over `fetch` precisely
  because it survives page unload; it is now patched alongside `fetch` and `XMLHttpRequest`.

## v0.2.0 — every release had shipped a different signing certificate

Reported directly: "unless I remove the app and install it again, I cannot get the browser to
work." Comparing the three prior APKs' signer certificates showed three different SHA-256
fingerprints. The build container is disposable and `build.sh` persisted `/root/.gradle` but not
`/root/.android`, so Gradle minted a fresh debug keystore on every single build — Android refuses
to upgrade an app whose signing certificate changed
(`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), so uninstalling was the only option, taking bookmarks,
history and cookies with it every time.

Fixed with a stable 4096-bit key generated once and kept outside the repository, supplied to the
build through environment variables, with a fallback to ordinary debug signing when it is absent
(so a fresh clone still builds). `versionCode` was also switched from a hardcoded `102` to
`1000 + commit count`, since Android only treats an install as an upgrade when the version code
rises. One last uninstall was needed to move onto the stable key; every release since has upgraded
in place.

## v0.3.0 — the fork was invisible to F-Droid as a separate app

Reported directly: F-Droid recognised the installed app as upstream's "Lightning" and offered no
way to install the real one alongside it. The cause: the fork had kept upstream's
`applicationId`, `acr.browser.lightning` — which *is* how Android and F-Droid decide whether two
APKs are the same app, regardless of what either is actually built from.

Changed `applicationId` to `com.razvanfarte.lightningdevtools` (and `.lite` for the barebones
flavour). The launcher label moves from `@string/app_name` — translated in twenty locales, so a
flavour-level override would have left non-English devices still reading "Lightning" — to a
manifest placeholder resolved per flavour, giving an English label (`Lightning DevTools`)
regardless of device locale. `namespace` was deliberately left as `acr.browser.lightning`: it only
controls where `R` and `BuildConfig` are generated, has no bearing on install identity, and
changing it would touch every source file for no benefit. Because the package name defines app
identity, this installed as a new app rather than upgrading — one further, final uninstall of the
old identity.

## v0.4.0 — new visual identity

Requested directly, alongside this documentation: a logo that is recognisably a variant of
upstream's rather than a copy, so the two are easy to tell apart on a home screen as well as by
name. Replaces the blue/orange cloud-and-bolt with a dark-slate `<bolt>` glyph — angle brackets for
"developer tool" framing the same lightning-bolt lineage, rendered as Android vector drawables
(resolution-independent, no per-density rasters to keep in sync) with a themed monochrome layer for
Android 13+. Source lives at `art/icon.svg`. This release changes only resources, not signing or
package identity, so it is the first icon change that upgrades in place with no reinstall.

Also the point at which this `docs/` structure and the rewritten `README.md` were added, to make
the reasoning behind all of the above legible without re-reading every commit message.
