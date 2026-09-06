# Architecture decisions

Why the project is shaped the way it is. Each entry states the choice, the alternative that was
considered, and the reasoning that decided between them — written so a future change can check
whether the original reasoning still holds before overriding it.

## Base: fork Lightning-Browser rather than build a new browser

**Candidates considered:** anthonycr/Lightning-Browser, Slion/Fulguris, FaFre/WebLibre (Flutter +
GeckoView), uazo/cromite (Chromium fork), mozilla-firefox/firefox.

**Chosen:** Lightning-Browser. It already depended on OkHttp 5.5.0, kotlinx.serialization, and
Compose/Material3 — the exact stack a HAR recorder needs — and had a `shouldInterceptRequest` hook
already in place as an insertion point. WebLibre would have meant a second language toolchain
(Dart/Flutter) on top of Android's; Cromite and Firefox are hours-long builds and multi-gigabyte
repositories, infeasible on this project's 8 GB RAM Docker host with no KVM for even testing.

## Engine: Android WebView (`androidx.webkit`), not GeckoView

GeckoView would allow the DevTools UI to ship as a WebExtension, matching how Firefox's
MobiDevTools reference point works. It was rejected because it pulls in ~70 MB of engine and,
critically, the reference implementation's worst-reviewed problems (inspect-element became
read-only after an update, network panel empties on reload, scroll gestures read as taps) are
properties of *building the inspector as a web page*, not of the browser engine underneath. Native
WebView plus a native Compose panel sidesteps that class of bug entirely rather than trying to fix
it inside a WebExtension.

## Two independent capture layers, not one

Native interception (`NetworkRecorder`, replaying requests through OkHttp) and the injected page
agent (`DevToolsAgent.js`) each see things the other structurally cannot:

- Native sees every request, but `shouldInterceptRequest` never exposes a request body, and no
  Android API recovers one. A replayed POST with a substituted empty body is not a close
  approximation — it broke every login form until this was understood (see PROGRESS.md v0.1.1).
- The page agent sees the real body of `fetch`/XHR/`sendBeacon` calls, because it hooks them before
  the browser sends anything. But it can never see the `Cookie` header — forbidden to scripts by
  the fetch/XHR specs, and HttpOnly cookies are invisible to JavaScript by design regardless.

Neither layer is sufficient alone. `NetworkRecorder.addAgentEntry` merges an agent report into its
native counterpart (matched by method, URL, and a 5-second window) rather than keeping both, so the
visible log has one entry per exchange carrying the best available data from each side.

## Redaction is on by default for HAR export, and never applied to `curl` export

The first real exported HAR contained a login password in cleartext, because a HAR file is exactly
what it claims to be: a faithful record of what was typed. `HarRedactor` strips passwords, tokens,
`Authorization` and `Cookie` from every export unless a user explicitly opts out via the panel
checkbox.

The `curl` export deliberately does **not** redact, even though it shares the same panel as HAR
export. A curl command stripped of its cookies and auth headers does not run — redacting it would
silently produce a command that looks correct and fails, which is worse than not redacting at all
for a feature whose entire purpose is "run this again." The UI states outright that what was copied
is a live credential rather than pretending it is safe to paste anywhere.

## `CurlBuilder` reads cookies from `CookieManager`, not from the recorded entry

Because agent-captured requests can never carry a `Cookie` header (see above), building a `curl`
command purely from recorded data would produce something that runs and gets a 401 — silently
wrong in the most misleading way possible. `CurlBuilder.build()` takes a `cookieHeader` parameter,
populated from Android's `CookieManager` at generation time, and only falls back to it when the
entry itself has no cookie already recorded. This was confirmed against a real authenticated
endpoint: a copied command for `GET /api/employers/8699/` returned an authenticated 200 with the
same `sessionid`, and the response's `Set-Cookie` for it was marked `HttpOnly` — proof the fallback
path, not the recorded data, was what made it work.

## Redirects are recorded, then declined, never returned

`WebResourceResponse` throws if given a 3xx status ("statusCode can't be in the [300, 399] range"),
so a replayed redirect can never be handed back to the WebView. The fix is not to avoid replaying
redirectable requests — that information isn't known until the response arrives — but to record the
hop from the response OkHttp already fetched, then return `null` so the WebView performs the
redirect itself. The practical cost is that redirected URLs are fetched twice (once by the
recorder, once by the WebView); accepted because a debugging tool prioritizes an accurate log over
minimizing a request that was going to be cheap and cached-friendly regardless.

## Signing key lives outside the repository, supplied through the environment

The Docker build container is disposable by design — that is what makes it safe to iterate in.
Without an explicit key, Gradle mints a new debug keystore inside the container on every run, so
every build produces a different signing certificate and Android refuses every subsequent
in-place upgrade. A stable key was generated once, stored at `../keystore/` (sibling to the repo,
not inside it — a keystore in git history is a keystore that can never be rotated without keeping
the compromise around forever), and passed to Gradle via `DEVTOOLS_KEYSTORE` and friends. The build
still succeeds without it, falling back to (unstable) debug signing, so a clone by someone without
the key isn't blocked — they simply won't get upgrade-in-place until they generate their own.

## `applicationId` changed from upstream's; `namespace` did not

`applicationId` is what Android's package manager and F-Droid use to decide whether two APKs are
"the same app" — keeping upstream's `acr.browser.lightning` meant this fork was invisible as a
separate install target. It is now `com.razvanfarte.lightningdevtools`.

`namespace` (the Kotlin/Java package under which `R` and `BuildConfig` are generated) stays
`acr.browser.lightning`. It has no bearing on install identity or F-Droid's ability to distinguish
apps, and renaming it would touch essentially every source file's `R.` reference for a purely
cosmetic gain. Changing the field that actually mattered was preferred over changing the field that
looked like it should.

## App label comes from a manifest placeholder, not a resource override

`@string/app_name` is translated in twenty locales (Russian renders it `Молния`). Overriding it in
one flavour's `values/strings.xml` would only change the default locale's string, leaving every
non-English device still showing the upstream name. `manifestPlaceholders["appLabel"]`, set per
flavour in `build.gradle.kts`, resolves to a single fixed string regardless of device locale — the
correct tool when the goal is "this app is called X everywhere," not "translate this app's name."

## Icon is a variant, not a copy, built as vector drawables

The new icon keeps the same underlying idea as upstream's — a lightning bolt as the central motif —
because the fork's lineage is real and worth acknowledging, not disguising. But the silhouette
(angle brackets instead of a cloud and a circle split), palette (dark slate and terminal green
instead of blue and orange) and mood (developer tool, not consumer browser) are all deliberately
different, so the two are unmistakable side by side rather than a recolour someone might mistake
for the same app.

Built as Android vector drawables (`res/drawable/ic_launcher_{background,foreground,monochrome}.xml`)
rather than per-density PNGs, because the previous per-density rasters were already dead weight:
`minSdk 28` guarantees the `mipmap-anydpi` adaptive-icon XML is always resolved ahead of any
density-specific fallback, so those PNGs could never have been shown at runtime. A `monochrome`
layer was added for Android 13+ themed icons, which upstream's icon never had. `art/icon.svg` is
kept as an SVG master purely for editing convenience (previewing without a full Android build); it
is not part of the build.
