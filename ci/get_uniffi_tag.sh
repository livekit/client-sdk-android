#!/bin/bash
set -e
set -x

# Prints the rust-sdks git tag matching the livekit-uniffi version this SDK builds against,
# e.g. `livekit-uniffi/v0.1.12`. Use it to check out the core that produced the published
# bindings -- notably when building the host library that unit tests load through JNA.

CATALOG=./gradle/libs.versions.toml

# Only the [versions] entry: `livekit-uniffi` also names a [libraries] entry, and a version
# catalog may declare a library as a plain "group:name:version" string.
UNIFFI_VERSION=$(awk '
  /^\[/ { section = $0 }
  section == "[versions]" && /^livekit-uniffi[[:space:]]*=/ {
    if (match($0, /"[^"]*"/)) { print substr($0, RSTART + 1, RLENGTH - 2); exit }
  }
' "$CATALOG")

if [ -z "$UNIFFI_VERSION" ]; then
  >&2 echo "error: no livekit-uniffi version under [versions] in $CATALOG"
  exit 1
fi

>&2 echo "livekit-uniffi version: $UNIFFI_VERSION"

echo "livekit-uniffi/v$UNIFFI_VERSION"
