#!/bin/bash
set -euo pipefail
PROJECT="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$PROJECT/.deps"
TMP_JAR="$(mktemp "$PROJECT/.deps/xposed-api-82.XXXXXX")"
trap 'rm -f "$TMP_JAR"' EXIT
curl -fsSL --retry 2 --connect-timeout 15 \
    https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar -o "$TMP_JAR"
EXPECTED=f48c635f1c7469fdec0e00ad2ea0b7a6b2f5b55065784a35b7ca3a84615e8e25
if command -v sha256sum >/dev/null 2>&1; then
    ACTUAL="$(sha256sum "$TMP_JAR" | cut -d ' ' -f 1)"
else
    ACTUAL="$(shasum -a 256 "$TMP_JAR" | cut -d ' ' -f 1)"
fi
if [ "$ACTUAL" != "$EXPECTED" ]; then
    echo "Xposed API checksum mismatch" >&2
    exit 1
fi
mv "$TMP_JAR" "$PROJECT/.deps/xposed-api-82.jar"
echo "Verified Xposed API 82"
