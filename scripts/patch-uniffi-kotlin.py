#!/usr/bin/env python3
# Copyright 2026 LiveKit, Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Work around two UniFFI Kotlin codegen bugs in the generated livekit-uniffi bindings.

TEMPORARY LOCAL-DEV TOOL. The data stream FFI in livekit-uniffi has so far only been
consumed from Swift, Python and Node; nobody had generated its Kotlin bindings before
(`packages/kotlin/` did not exist in the rust-sdks tree). Doing so surfaces two codegen
bugs that make the generated file fail to compile. Both need a real upstream fix -- see
the notes on each patch below -- but neither is fixable from the consuming side, so this
script patches the generated output in place.

Nothing in the rust-sdks tracked source is modified: `packages/kotlin/` is a build
artifact, and this mirrors the precedent already set by the `swift-workarounds` task in
livekit-uniffi/Makefile.toml, which perl-patches generated Swift for a different uniffi
bug (mozilla/uniffi-rs#2933).

Run after `uniffi-bindgen generate --language kotlin` and before assembling the AAR.
Idempotent, and fails loudly if the expected patch sites are not found -- so a uniffi
upgrade that changes or fixes the codegen surfaces as an error here rather than as
silently unpatched (or double-patched) output.

Usage:
    scripts/patch-uniffi-kotlin.py <path to packages/kotlin>
"""

import re
import sys
from pathlib import Path

# Rust objects whose exported `close()` collides with the generated AutoCloseable.close().
WRITERS_WITH_CLOSE = ["ByteStreamWriter", "TextStreamWriter"]

# Error types with a variant field literally named `message`.
ERROR_TYPE = "DataStreamException"
ERROR_CONVERTER = "FfiConverterTypeDataStreamError"
RENAMED_FIELD = "reason"

TOP_LEVEL_DECL = re.compile(
    r"^(?:public |internal |open |sealed |data |enum )*(?:class|object|interface|typealias|fun|val) ",
    re.M,
)


def region(src: str, start_pattern: str) -> tuple[int, int]:
    """Byte range of one top-level declaration, from its header to the next one."""
    m = re.search(start_pattern, src, re.M)
    if not m:
        raise SystemExit(f"patch-uniffi-kotlin: could not find {start_pattern!r}")
    nxt = TOP_LEVEL_DECL.search(src, m.end())
    return m.start(), (nxt.start() if nxt else len(src))


def patch_writer_close(src: str, cls: str) -> tuple[str, int]:
    """Drop AutoCloseable from a writer class so its Rust `close()` can exist.

    UniFFI gives every object a non-suspend `close()` (aliasing `destroy()`) to satisfy
    AutoCloseable. When the Rust object *also* exports a method named `close`, the two
    differ only by `suspend` and Kotlin rejects them as conflicting overloads, so the
    whole file fails to compile.

    Removing AutoCloseable (and its close() alias) keeps the Rust-facing `close()` --
    which Swift already depends on, so renaming the Rust method is not an option --
    while `Disposable.destroy()` remains for releasing the handle. The cost is that
    these two types can no longer be used with Kotlin's `use {}`.

    Upstream fix would be for uniffi to name its resource-release method something that
    cannot collide, or to skip AutoCloseable when the interface declares `close`.
    """
    start, end = region(src, rf"^open class {cls}: ")
    body = src[start:end]
    changed = 0

    supertypes = f"open class {cls}: Disposable, AutoCloseable, {cls}Interface"
    if supertypes in body:
        body = body.replace(
            supertypes,
            f"open class {cls}: Disposable, {cls}Interface",
            1,
        )
        changed += 1

    alias = "    @Synchronized\n    override fun close() {\n        this.destroy()\n    }\n"
    if alias in body:
        body = body.replace(alias, "", 1)
        changed += 1

    return src[:start] + body + src[end:], changed


def patch_error_message_field(src: str) -> tuple[str, int]:
    """Rename error variant fields named `message` so they stop colliding.

    UniFFI emits, for a structured error variant carrying a `message` field:

        class AbnormalEnd(val `message`: kotlin.String) : DataStreamException() {
            override val message get() = "message=${ `message` }"
        }

    The constructor property and the overridden `Throwable.message` are both called
    `message` in one class body, which does not compile (and their types differ --
    String vs String? -- so they cannot be merged into one override either).

    Renaming the constructor property sidesteps it. `Throwable.message` still carries
    the text, and the raw string is reachable as `.reason`.

    Upstream fix would be for uniffi to avoid emitting a property that collides with
    Throwable.message (or for livekit-uniffi to not name the field `message`).
    """
    changed = 0
    for pattern in (rf"^sealed class {ERROR_TYPE}", rf"^public object {ERROR_CONVERTER}"):
        start, end = region(src, pattern)
        body = src[start:end]
        before = body
        # The constructor property declaration, and reads of it. `override val message`
        # is written without backticks, so it is untouched by these.
        body = body.replace("val `message`: kotlin.String", f"val `{RENAMED_FIELD}`: kotlin.String")
        body = body.replace("${ `message` }", f"${{ `{RENAMED_FIELD}` }}")
        body = body.replace("value.`message`", f"value.`{RENAMED_FIELD}`")
        if body != before:
            changed += 1
        src = src[:start] + body + src[end:]
    return src, changed


def main() -> int:
    if len(sys.argv) != 2:
        raise SystemExit(f"usage: {sys.argv[0]} <path to packages/kotlin>")

    target = Path(sys.argv[1]) / "io" / "livekit" / "uniffi" / "livekit_uniffi.kt"
    if not target.is_file():
        raise SystemExit(f"patch-uniffi-kotlin: {target} not found; run the Kotlin bindgen first")

    src = original = target.read_text()

    total = 0
    for cls in WRITERS_WITH_CLOSE:
        src, n = patch_writer_close(src, cls)
        total += n
    src, n = patch_error_message_field(src)
    total += n

    if src == original:
        # Either already patched or the codegen changed. Distinguish, so a uniffi
        # upgrade that alters these shapes cannot pass silently.
        already = "AutoCloseable" not in src.split("open class ByteStreamWriter: ")[1][:200]
        if already:
            print("patch-uniffi-kotlin: already patched, nothing to do")
            return 0
        raise SystemExit(
            "patch-uniffi-kotlin: found no patch sites and the file is not already patched.\n"
            "The uniffi codegen likely changed. Re-check whether these bugs still exist "
            "before removing this script."
        )

    target.write_text(src)
    print(f"patch-uniffi-kotlin: applied {total} patch(es) to {target}")

    # Cheap self-check: the exact symptoms must be gone.
    leftovers = []
    for cls in WRITERS_WITH_CLOSE:
        head = src.split(f"open class {cls}: ")[1][:200]
        if "AutoCloseable" in head:
            leftovers.append(f"{cls} still implements AutoCloseable")
    if "val `message`: kotlin.String" in src.split(f"sealed class {ERROR_TYPE}")[1].split("public object")[0]:
        leftovers.append(f"{ERROR_TYPE} still declares a `message` field")
    if leftovers:
        raise SystemExit("patch-uniffi-kotlin: incomplete: " + "; ".join(leftovers))

    return 0


if __name__ == "__main__":
    sys.exit(main())
