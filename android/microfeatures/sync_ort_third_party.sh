#!/usr/bin/env bash
# Sync ORT C API headers + libonnxruntime.so for native CMake linking.
# Headers: from onnxruntime-reduced.aar.bak-minsdk24 (current AAR may omit headers/)
# Libs: from app/libs/onnxruntime-reduced.aar (must match APK runtime version)

set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
AAR="$ROOT/app/libs/onnxruntime-reduced.aar"
HDR_AAR="$ROOT/app/libs/onnxruntime-reduced.aar.bak-minsdk24"
DEST="$ROOT/microfeatures/src/main/cpp/third_party/ort"

if [[ ! -f "$AAR" ]]; then
  echo "error: missing $AAR" >&2
  exit 1
fi
if [[ ! -f "$HDR_AAR" ]]; then
  echo "error: missing $HDR_AAR (needed for C API headers)" >&2
  exit 1
fi

mkdir -p "$DEST/include" "$DEST/lib/arm64-v8a" "$DEST/lib/armeabi-v7a"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

unzip -q -o "$HDR_AAR" "headers/*" -d "$TMP"
cp "$TMP"/headers/*.h "$DEST/include/"

unzip -q -o "$AAR" \
  "jni/arm64-v8a/libonnxruntime.so" \
  "jni/armeabi-v7a/libonnxruntime.so" \
  -d "$TMP"
cp "$TMP/jni/arm64-v8a/libonnxruntime.so" "$DEST/lib/arm64-v8a/"
cp "$TMP/jni/armeabi-v7a/libonnxruntime.so" "$DEST/lib/armeabi-v7a/"

echo "Synced ORT third_party to $DEST"
