<div align="center">
  <img src="launcher_icon.png" width="120" height="120" alt="Lightning DevTools icon">

  # Lightning DevTools

  **A mobile Android browser with on-device DevTools: record network sessions, export
  HAR files, and copy any request as a runnable `curl` command — no laptop required.**
</div>

---

## Why this exists

Chrome and Safari's DevTools live entirely on the desktop, reached over USB with
`chrome://inspect` or Safari's Web Inspector. On a phone with no laptop nearby, there is nothing:
DuckDuckGo, Opera and stock WebView browsers ship no network panel at all. Firefox for Android is
the closest thing, via community add-ons like MobiDevTools — but those inspect the page from
*inside* the page, and inherit its problems: the log empties on reload, inspecting an element
often can't edit it, and scroll gestures get read as taps.

This is a fork of [Lightning-Browser](https://github.com/anthonycr/Lightning-Browser) with the
DevTools moved into native code instead: a real network recorder, a real HAR 1.2 exporter, and a
native Compose panel that scrolls like the rest of Android because it isn't drawn inside the web
page it's inspecting.

**Scope, stated plainly:** this records traffic from *this browser's own tabs*. Android's app
sandbox makes it structurally impossible for any browser to see another app's network traffic —
that needs a system-level tool like PCAPdroid, and even then, certificate pinning defeats it. If
what you need is "why did my banking app just do that," this is the wrong tool. If it's "what did
this web page just send," this is built for exactly that.

## Features

- **Network recording** with real HAR 1.2 timings, request/response headers, and bodies — captured
  through two complementary layers (native replay via OkHttp, plus a page-injected agent) because
  neither alone can see everything a request needs.
- **HAR export** to Downloads, or straight into a share sheet, viewable in Chrome DevTools, Firefox,
  or Charles with no conversion step.
- **Copy any request as `curl`** — long-press a network row, or its `⋮` button — complete with
  cookies pulled live from the browser's cookie store, since a script-based capture can never see
  an HttpOnly cookie.
- **Console capture**, including uncaught errors and unhandled promise rejections, surviving page
  reloads because it's held in native memory, not in the page.
- **Credential redaction by default.** Passwords, tokens, `Authorization` and `Cookie` are stripped
  from exported HAR files unless you explicitly turn it off. (`curl` export is the deliberate
  exception — a redacted command can't run — and says so.)
- Everything upstream Lightning-Browser already does: bookmarks, history, multiple search engines,
  incognito mode, drawer or bottom-bar tabs, ad blocking.

Full reasoning for each of these — what was tried, what a real exported HAR turned up, what fixed
it — is in [`docs/PROGRESS.md`](docs/PROGRESS.md) and [`docs/DECISIONS.md`](docs/DECISIONS.md).

## Using DevTools

Overflow menu → **DevTools**. Recording starts automatically when the panel opens.

1. Load or reload the page you want to inspect.
2. **Network** tab: tap a row to expand it (headers, timings, bodies); long-press, or its `⋮`
   button, to copy it as `curl` or send it elsewhere.
3. **Export HAR** writes to Downloads; **Share** sends the file directly to another app.
4. **Console** tab shows `console.*` output and uncaught errors as they happen.

## Install

Grab the latest APK from [Releases](../../releases).

The package is `com.razvanfarte.lightningdevtools` — distinct from upstream Lightning's
`acr.browser.lightning` — so it installs alongside the real Lightning-Browser rather than
conflicting with it.

## Building

The host this was developed on has no JDK, Android SDK, or Gradle installed — everything builds
inside Docker.

```bash
docker build -t devtools-browser-build:latest tools/
./tools/build.sh                              # -> :app:assembleLightningPlusDebug
./tools/build.sh :app:testLightningPlusDebugUnitTest --tests "acr.browser.lightning.browser.devtools.*"
```

Output: `app/build/outputs/apk/lightningPlus/debug/app-lightningPlus-debug.apk`

For the APK to **upgrade in place** rather than requiring a reinstall, it needs to be signed with a
consistent key across builds — see [`tools/README.md`](tools/README.md) for how that's supplied.

## Architecture, in brief

```
WebView request
      │
      ├─ shouldInterceptRequest (native) ──▶ NetworkRecorder ──▶ OkHttp replay
      │                                           │                  │
      │                                           │          TimingEventListener
      │                                           ▼                  │
      │                                     RecordedEntry ◀──────────┘
      │                                           ▲
      └─ page agent (JS, document-start) ─────────┘
              fetch / XHR / sendBeacon patched,
              reports over addWebMessageListener
                                                   │
                                     merge by (method, url, time window)
                                                   │
                                    ┌──────────────┼──────────────┐
                                    ▼              ▼              ▼
                              DevToolsPanel   HarExporter    CurlBuilder
                               (Compose UI)  (+ HarRedactor)  (+ CookieManager)
```

Native interception sees every request but never its body (no Android API exposes one); the page
agent sees bodies but can never see the `Cookie` header (forbidden to scripts by the fetch/XHR
specs, and HttpOnly cookies are invisible to JavaScript regardless). Each layer's report is merged
into one entry carrying the best available data from both. The full rationale for every non-obvious
choice here — why redaction defaults on, why `curl` export doesn't, why redirects are recorded and
then declined — is in [`docs/DECISIONS.md`](docs/DECISIONS.md).

## Known limitations

See [`docs/TODO.md`](docs/TODO.md) for the complete, current list. The two worth knowing up front:

- A plain HTML `<form method="post">` submission (not `fetch`/XHR) is not captured — only `fetch`,
  `XMLHttpRequest` and `sendBeacon` are patched.
- Nothing here has been verified on an emulator; this development machine has no KVM. Everything
  shipped has been verified by compiling, unit testing, and inspecting the built APK, then
  confirmed against real exported sessions — but "runs correctly on a real phone" is the standard
  that matters, and that verification happens on-device, after each release.

## Contributing

This is a personal fork built for a specific need rather than a general-purpose project, but issues
and pull requests are welcome. See [`docs/TODO.md`](docs/TODO.md) for what's already known to be
incomplete before opening an issue for it.

## Credits and license

A fork of [Lightning-Browser](https://github.com/anthonycr/Lightning-Browser) by Anthony Restaino,
under the same license:

```
Copyright 2014 Anthony Restaino

Lightning Browser

   This Source Code Form is subject to the terms of the
   Mozilla Public License, v. 2.0. If a copy of the MPL
   was not distributed with this file, You can obtain one at

   http://mozilla.org/MPL/2.0/
```

The DevTools additions in this fork (`browser/devtools/`, `js/DevToolsAgent.js`, `art/`) are
likewise MPL-2.0, consistent with the rest of the codebase they extend.
