# Open work

Ordered roughly by how much it would change what the tool is useful for, not by effort. Each item
states why it's not already done, so picking one up starts from the real constraint rather than
from scratch.

## Known gaps

- **Plain HTML form submissions are invisible.** Since POSTs are no longer replayed natively (see
  `docs/DECISIONS.md`, "two independent capture layers"), the only source for a POST is the page
  agent, and the agent only patches `fetch`, `XMLHttpRequest` and `sendBeacon`. A traditional
  `<form method="post">` submit that triggers a full navigation goes through neither and is not
  captured at all — you would see the *response* page load but no request entry for the submit
  itself. Fixing this means a `submit` event listener on the agent that serializes `FormData` from
  the form before the browser navigates away, which is a real gap, not a hard one.

- **Cross-origin `no-cors` responses stay opaque.** By web platform design, a script can never read
  the status or headers of an opaque response, and the native layer can't fill the gap because
  those requests are exactly the ones with bodies that can't be replayed. These entries are now
  flagged (`opaque: true`) rather than looking like failures, but the response itself is genuinely
  unrecoverable — this is a platform limit, not a bug to fix.

- **No DOM/Elements inspector.** The original ask (mirroring what people wanted from Firefox's
  MobiDevTools) included element inspection alongside network and console. Only network and console
  exist so far. This is the largest remaining scope item.

## Needs device verification

Nothing in this list has been disproven — it just hasn't been checked against real hardware yet,
because this development machine has no KVM (`/dev/kvm` absent, no `vmx`/`svm` CPU flags) and
therefore cannot run an Android emulator at usable speed. Everything below was verified by
compiling, unit-testing, and inspecting the built APK's contents, not by running the app.

- **Long-press gesture on network rows.** `combinedClickable` was added to a row that also handles
  tap-to-expand via `onClick`. If tap-to-expand ever stops working after a future change, this
  interaction is the first place to check for a regression.
- **The monochrome (Android 13+ themed) icon layer** has never been seen rendered by a real
  launcher — only confirmed to compile and be referenced correctly from `ic_launcher.xml`.
- **Redirect double-fetch behaviour** under real network conditions (slow connections, redirect
  chains longer than one hop) has only been reasoned about, not observed.

## Possible follow-ups, not yet requested

- A settings screen for the capture body-size cap (`MAX_CAPTURED_BODY_BYTES`, currently a fixed
  1 MB) and the entry ring-buffer size (`MAX_ENTRIES`, currently 1500), if a real session turns out
  to need either changed.
- Import of a previously exported HAR back into the panel, for offline review without needing to
  reproduce the session.
- A CI workflow that runs the Docker build on push, so a release doesn't depend on this specific
  development machine having the container image cached.
