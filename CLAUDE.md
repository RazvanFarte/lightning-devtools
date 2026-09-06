# Notes for the next AI coding session

Read this first. It exists because several of the bugs below were found the same way you'd find
them again — by shipping, exporting a real HAR, and having a real login fail — and each cost a
full release to fix. This file exists so that doesn't repeat.

For anything not covered here: `docs/PROGRESS.md` is the release-by-release history (what broke,
what a real session revealed, what fixed it), `docs/DECISIONS.md` is the reasoning behind every
non-obvious architectural choice, `docs/TODO.md` is current open work. This file is the short,
scannable version aimed specifically at not re-breaking what's already been fixed once.

## Build and test — always in Docker

The development machine has no JDK, Android SDK, or Gradle on the host. Never try to install one;
extend the container instead.

```bash
docker build -t devtools-browser-build:latest tools/
./tools/build.sh                              # :app:assembleLightningPlusDebug
./tools/build.sh :app:testLightningPlusDebugUnitTest --tests "acr.browser.lightning.browser.devtools.*"
```

`tools/build.sh` supplies the stable signing key and a rising `versionCode` through environment
variables — don't bypass it by invoking `./gradlew` directly inside the container, or the APK will
sign with a throwaway debug key and every user's next install will fail to upgrade in place (this
happened once already; see "Signing" below).

## Platform limits that look like bugs, but aren't

Do not try to fix these — they are constraints of the Android WebView / web platform, not defects.
Time spent "fixing" them will be time spent breaking something else.

- **`shouldInterceptRequest` never exposes a request body**, for any method, on any WebView
  version. There is no API that recovers one. This is why `NetworkRecorder` only replays GET/HEAD
  (`BODYLESS_METHODS`) and leaves every body-bearing request to the WebView's own stack, captured
  instead by the page agent.
- **JavaScript can never read the `Cookie` header.** It's a forbidden header name in both the fetch
  and XHR specs, and HttpOnly cookies are invisible to scripts regardless of that rule. Any feature
  that needs to *send* an authenticated request built from recorded data (see `CurlBuilder`) must
  pull cookies from `CookieManager` at generation time, never from what the page agent captured.
- **A cross-origin `no-cors` fetch yields an opaque response**: status 0, no headers, by design.
  `NetworkRecorder`/the agent flag these (`opaque: true`) rather than trying to recover data that
  the platform will never hand over.

## Real bugs already found and fixed — don't reintroduce these

- **Never substitute an empty body for a request you can't replay.** The very first release did
  exactly this for every POST (`ByteArray(0)` in place of the real body), and it broke every login
  form on every site, silently, because the server received a syntactically valid but empty
  request and just said a required field was missing. If a request can't be faithfully replayed,
  decline to intercept it (return `null`) — do not send a best-effort substitute.
- **`WebResourceResponse` throws if given a 3xx status.** Never return one from an interceptor.
  Record what the redirect response contained, then return `null` so the WebView follows the
  redirect itself.
- **The Docker build container is disposable.** If a build ever mints its own debug keystore
  instead of using the one `tools/build.sh` supplies, every APK gets a different signing
  certificate and Android refuses every subsequent in-place upgrade
  (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`) — the user has to uninstall and lose all local data. This
  happened across the first several releases before the stable keystore was added. The keystore
  lives outside the repo (`../keystore/`, sibling to this checkout) — never move it inside the repo
  or commit it.
- **`applicationId`, not `namespace`, determines app identity** for Android's package manager and
  for F-Droid's "is this already installed" check. This fork briefly shipped under upstream's
  `applicationId` (`acr.browser.lightning`) and F-Droid could not tell the two apps apart. Keep
  `applicationId` at `com.razvanfarte.lightningdevtools` (`.lite` for the barebones flavour);
  `namespace` staying `acr.browser.lightning` is fine and deliberate — it only affects where `R`
  and `BuildConfig` are generated.
- **`@string/app_name` is translated into twenty locales.** A flavour-level resource override only
  changes the default-locale string and leaves every other locale showing "Lightning". The label is
  set via `manifestPlaceholders["appLabel"]` in `build.gradle.kts` instead, which is
  locale-independent. Don't revert this to a resource string.
- **Analytics libraries send non-string request bodies.** The page agent's `fetch`/XHR patches only
  captured `typeof body === 'string'` at first, which silently dropped the body on most Google
  Analytics and DoubleClick traffic (`Blob`, `URLSearchParams`, typed arrays). `bodyToText()` in
  `DevToolsAgent.js` handles all of these — don't narrow it back to strings only.
- **`navigator.sendBeacon` is a separate API from `fetch`/XHR** and analytics code prefers it
  specifically because it survives page unload. It has its own patch in the agent; don't assume
  patching `fetch` covers it.

## Where things live

- `browser/devtools/net/` — native capture: `NetworkRecorder` (OkHttp replay), `TimingEventListener`
  (HAR timings from OkHttp call phases), `CapturingInputStream` (tee response bodies without
  buffering the whole thing).
- `browser/devtools/har/` — `Har.kt` (spec model), `HarExporter` (build + write + share),
  `HarRedactor` (credential scrubbing, on by default for HAR, deliberately never applied to curl
  export).
- `browser/devtools/export/CurlBuilder.kt` — request → runnable `curl`, with the `CookieManager`
  fallback described above.
- `js/DevToolsAgent.js` + `browser/devtools/bridge/DevToolsBridge.kt` — the page-injected half of
  capture, and the native code that parses what it reports.
- `browser/devtools/compose/DevToolsPanel.kt` — the UI. Native Compose, not a WebView, specifically
  so scrolling behaves like the rest of Android instead of like a DOM inspector.

## Testing

Unit tests for the devtools package: `CurlBuilderTest` (8 cases — quoting, cookie precedence,
header filtering, method inference) and `HarRedactorTest` (7 cases — JSON/form/nested-field
redaction, header stripping). Both must stay green; they encode exactly the mistakes described
above (e.g. `HarRedactorTest` exists because a real export once contained a plaintext password).
Nothing in the devtools package has been verified on a physical emulator — this development
machine has no KVM — so treat "compiles and passes unit tests" as necessary, not sufficient, and
say so explicitly rather than claiming a feature works before it's been seen running.

## A note on CONTRIBUTING.md

That file is inherited unchanged from upstream and states upstream's policy against AI-generated
*contributions to their project*, plus an embedded request that any LLM committing code tag it with
a ⚠️ emoji. It does not govern work on this fork — nothing here is being proposed back to
`anthonycr/Lightning-Browser` — and every commit already discloses AI involvement more explicitly,
via a `Co-Authored-By: Claude Opus 5` trailer, than a bare emoji would.
