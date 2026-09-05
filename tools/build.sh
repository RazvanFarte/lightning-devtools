#!/usr/bin/env bash
# Build Lightning-Browser (+ our DevTools work) inside the pinned Android SDK 37 container.
# Usage: ./build.sh [gradle-task ...]     default: :app:assembleLightningPlusDebug
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KEYSTORE_DIR="${DEVTOOLS_KEYSTORE_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../../keystore" && pwd)}"
IMAGE="devtools-browser-build:latest"
CACHE_VOL="devtools-browser-gradle-cache"
ANDROID_VOL="devtools-browser-android-home"
TASKS=("${@:-:app:assembleLightningPlusDebug}")

docker volume create "$CACHE_VOL" >/dev/null
# Persist /root/.android too. Without it Gradle mints a fresh debug keystore on every run inside
# the disposable container, every APK gets a different signature, and Android then refuses to
# upgrade in place. The explicit signing config below is the real fix; this is a second line of
# defence for any task that still falls back to debug signing.
docker volume create "$ANDROID_VOL" >/dev/null

# Android treats an install as an upgrade only when versionCode increases.
VERSION_CODE=$(( 1000 + $(git -C "$REPO" rev-list --count HEAD) ))

docker run --rm \
  --name devtools-browser-build-run \
  --user root \
  -v "$REPO":/application \
  -v "$KEYSTORE_DIR":/signing:ro \
  -v "$CACHE_VOL":/root/.gradle \
  -v "$ANDROID_VOL":/root/.android \
  -e GRADLE_USER_HOME=/root/.gradle \
  -e DEVTOOLS_KEYSTORE=/signing/devtools.keystore \
  -e DEVTOOLS_KEYSTORE_PASSWORD=devtools-local \
  -e DEVTOOLS_KEY_ALIAS=devtools \
  -e DEVTOOLS_KEY_PASSWORD=devtools-local \
  -e DEVTOOLS_VERSION_CODE="$VERSION_CODE" \
  -w /application \
  "$IMAGE" \
  ./gradlew "${TASKS[@]}" \
    --no-daemon --stacktrace --max-workers=2 \
    -Dorg.gradle.jvmargs="-Xmx3072m -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8" \
    -Dkotlin.daemon.jvmargs="-Xmx1536m" \
    -Pkotlin.parallel.tasks.in.project=false

# hand the tree back to the host user
docker run --rm --user root -v "$REPO":/application alpine \
  chown -R "$(id -u):$(id -g)" /application

echo "versionCode=$VERSION_CODE"
