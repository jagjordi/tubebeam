#!/usr/bin/env bash
# Builds SmartTube (stable flavour, debug-signed) with the add-to-queue intent patch applied, in Docker.
# Usage: smarttube/build.sh [checkout-dir]      APKs land in <checkout-dir>/smarttubetv/build/outputs/apk/ststable/debug/
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="${1:-$HERE/SmartTube}"
if [[ ! -d "$SRC/.git" ]]; then
  git clone --depth 1 https://github.com/yuliskov/SmartTube.git "$SRC"
fi
cd "$SRC"
git submodule update --init --recursive --depth 1
if git apply --check "$HERE/add-to-queue-intent.patch" 2>/dev/null; then
  git apply "$HERE/add-to-queue-intent.patch"
elif ! git apply --reverse --check "$HERE/add-to-queue-intent.patch" 2>/dev/null; then
  echo "Patch does not apply to this SmartTube revision; apply it by hand." >&2; exit 1
fi
docker build --network host --build-arg UID="$(id -u)" --build-arg GID="$(id -g)" -t smarttube-build:latest "$HERE"
chmod +x gradlew
docker run --rm --network host -u "$(id -u):$(id -g)" \
  -v "$PWD":/workspace -v smarttube-gradle-cache:/home/dev/.gradle \
  smarttube-build:latest ./gradlew --no-daemon :smarttubetv:assembleStstableDebug
ls -la smarttubetv/build/outputs/apk/ststable/debug/*.apk
