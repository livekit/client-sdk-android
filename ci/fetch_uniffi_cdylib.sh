#!/bin/bash
set -e

# Downloads the prebuilt livekit-uniffi core for the host platform and prints the directory
# holding it, for `-PlivekitUniffiLibDir`.
#
# Unit tests in livekit-android-test call the Rust core through JNA, which needs a *host* build
# of it -- the livekit-uniffi AAR carries only Android .so files. rust-sdks attaches host cdylibs
# to each livekit-uniffi release, so CI can fetch one instead of installing a Rust toolchain and
# building the core from source.
#
# Local development doesn't need this: gradle/uniffi-native-lib.gradle finds a build in a sibling
# rust-sdks checkout on its own.

OUT_DIR=${1:-./build/livekit-uniffi-cdylib}

# Absolute from here on. Gradle resolves `-PlivekitUniffiLibDir` against the *subproject*
# (livekit-android-test), so a path relative to the repo root would silently miss.
mkdir -p "$OUT_DIR"
OUT_DIR=$(cd "$OUT_DIR" && pwd)

TAG=$(./ci/get_uniffi_tag.sh 2>/dev/null)

# Host triple, derived without rustc -- avoiding a Rust install is the point of this script.
case "$(uname -s)" in
  Darwin) OS=apple-darwin ;;
  Linux)  OS=unknown-linux-gnu ;;
  *)      >&2 echo "error: unsupported OS $(uname -s)"; exit 1 ;;
esac
case "$(uname -m)" in
  arm64|aarch64) ARCH=aarch64 ;;
  x86_64|amd64)  ARCH=x86_64 ;;
  *)             >&2 echo "error: unsupported architecture $(uname -m)"; exit 1 ;;
esac
TRIPLE="$ARCH-$OS"

ARCHIVE="build-$TRIPLE.zip"
BASE_URL="https://github.com/livekit/rust-sdks/releases/download/$TAG"
DEST="$OUT_DIR/$TRIPLE"

# Already fetched for this tag: the marker records which tag the contents came from, so a
# version bump re-downloads rather than silently testing against the previous core.
if [ -f "$DEST/.tag" ] && [ "$(cat "$DEST/.tag")" = "$TAG" ]; then
  >&2 echo "livekit-uniffi $TAG for $TRIPLE already present"
  echo "$DEST"
  exit 0
fi

>&2 echo "fetching livekit-uniffi $TAG for $TRIPLE"

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

curl -fsSL --retry 3 -o "$WORK/$ARCHIVE" "$BASE_URL/$ARCHIVE"
curl -fsSL --retry 3 -o "$WORK/$ARCHIVE.sha256" "$BASE_URL/$ARCHIVE.sha256"

# The sidecar names the archive, so verify from its directory. Prefer sha256sum (coreutils) and
# fall back to shasum (always present on macOS). Both report "<file>: OK" on stdout, which would
# corrupt the directory path this script exists to print -- send it to stderr with the rest of
# the progress output.
if command -v sha256sum >/dev/null 2>&1; then
  (cd "$WORK" && sha256sum -c "$ARCHIVE.sha256" >&2)
else
  (cd "$WORK" && shasum -a 256 -c "$ARCHIVE.sha256" >&2)
fi

rm -rf "$DEST"
mkdir -p "$DEST"
unzip -o -q "$WORK/$ARCHIVE" -d "$DEST"
echo "$TAG" > "$DEST/.tag"

>&2 echo "extracted to $DEST"

echo "$DEST"
