#!/usr/bin/env bash
# Build Lightning-Browser (+ our DevTools work) inside the pinned Android SDK 37 container.
# Usage: ./build.sh [gradle-task ...]     default: :app:assembleLightningPlusDebug
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE="devtools-browser-build:latest"
CACHE_VOL="devtools-browser-gradle-cache"
TASKS=("${@:-:app:assembleLightningPlusDebug}")

docker volume create "$CACHE_VOL" >/dev/null

docker run --rm \
  --name devtools-browser-build-run \
  --user root \
  -v "$REPO":/application \
  -v "$CACHE_VOL":/root/.gradle \
  -e GRADLE_USER_HOME=/root/.gradle \
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
