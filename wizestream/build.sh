#!/usr/bin/env bash
# Builds WizeStream (a NewPipe fork) with "Send to TV" / "Queue on TV" in the stream long-press menu.
# Usage: wizestream/build.sh [checkout-dir]      APK: <checkout-dir>/app/build/outputs/apk/debug/
# Uses TubeBeam's own build image (JDK 21); AGP downloads the SDK parts it needs on first run.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
SRC="${1:-$HERE/WizeStream}"
if [[ ! -d "$SRC/.git" ]]; then
  git clone --depth 1 https://github.com/wizdom13/WizeStream.git "$SRC"
fi
cd "$SRC"
if git apply --check "$HERE/send-to-tubebeam.patch" 2>/dev/null; then
  git am --keep-cr "$HERE/send-to-tubebeam.patch" 2>/dev/null || git apply "$HERE/send-to-tubebeam.patch"
elif ! git apply --reverse --check "$HERE/send-to-tubebeam.patch" 2>/dev/null; then
  echo "Patch does not apply to this WizeStream revision; apply it by hand." >&2; exit 1
fi
(cd "$ROOT" && docker compose build)
chmod +x gradlew
docker run --rm --network host -u "$(id -u):$(id -g)" \
  -v "$PWD":/workspace -v wizestream-gradle-cache:/home/dev/.gradle \
  tubebeam-build:latest ./gradlew --no-daemon assembleDebug
ls -la app/build/outputs/apk/debug/*.apk
