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
