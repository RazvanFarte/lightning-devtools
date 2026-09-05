# Build tooling

The Android toolchain is not installed on the host; builds run in a container.

```bash
docker build -t devtools-browser-build:latest tools/
./tools/build.sh                                  # :app:assembleLightningPlusDebug
./tools/build.sh :app:compileLightningPlusDebugKotlin
```

`tools/Dockerfile` layers Android SDK 37 onto a Temurin 21 JDK base. `tools/build.sh` bind-mounts
the repo, keeps the Gradle cache in a named volume between runs, and caps memory so the build fits
in 8 GB of RAM.

Output: `app/build/outputs/apk/lightningPlus/debug/app-lightningPlus-debug.apk`

## Signing

The APK is signed with a **stable key held outside this repository**, at `../keystore/` by default
(override with `DEVTOOLS_KEYSTORE_DIR`). This matters more than it looks: the build container is
disposable, so without an explicit key Gradle generates a fresh debug keystore on every run, every
APK carries a different certificate, and Android refuses to upgrade in place with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`. The app then has to be uninstalled for each new build, losing
bookmarks, history and cookies.

`build.sh` passes the key through `DEVTOOLS_KEYSTORE`, `DEVTOOLS_KEYSTORE_PASSWORD`,
`DEVTOOLS_KEY_ALIAS` and `DEVTOOLS_KEY_PASSWORD`. If `DEVTOOLS_KEYSTORE` is unset or missing the
build still works and falls back to debug signing, but the resulting APK will not upgrade over a
stably-signed install.

**Do not lose the keystore.** Replacing it means one more uninstall-and-reinstall cycle for every
device already running a build signed with it.

To recreate one:

```bash
keytool -genkeypair -v -keystore devtools.keystore -alias devtools \
  -keyalg RSA -keysize 4096 -validity 10000
```

`build.sh` also sets `DEVTOOLS_VERSION_CODE` from the commit count, because Android only treats an
install as an upgrade when the version code increases.
