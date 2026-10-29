#!/bin/bash
set -e

# Prints the rust-sdks git tag matching the livekit-ffi version this SDK builds against,
# e.g. `livekit-ffi/v0.13.2`. Use it to check out the core that produced the published
# bindings -- notably when building the host library that unit tests load through JNA.

CATALOG=./gradle/libs.versions.toml

# Only the [versions] entry: `livekit-ffi` also names a [libraries] entry, and a version
# catalog may declare a library as a plain "group:name:version" string.
UNIFFI_VERSION=$(awk '
  /^\[/ { section = $0 }
  section == "[versions]" && /^livekit-ffi[[:space:]]*=/ {
    if (match($0, /"[^"]*"/)) { print substr($0, RSTART + 1, RLENGTH - 2); exit }
  }
' "$CATALOG")

if [ -z "$UNIFFI_VERSION" ]; then
  >&2 echo "error: no livekit-ffi version under [versions] in $CATALOG"
  exit 1
fi

>&2 echo "livekit-ffi version: $UNIFFI_VERSION"

echo "livekit-ffi/v$UNIFFI_VERSION"
