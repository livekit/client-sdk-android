---
"client-sdk-android": patch
---

Keep dynacast-paused simulcast layers paused across publisher renegotiations. Previously a paused layer started to upload again after any renegotiation of the publisher connection. It stayed on until the next subscribed-quality change. Publishing a track, unpublishing one, a codec change, and an ICE restart all caused this.

The server's answer to a publisher offer no longer carries the RFC 8853 pause markers. Applying that answer re-enables every layer the client paused. If the subscribed set does not change, the server sends no subscribed-codec update, so the pause was never restated. Directly after it applies a publisher answer, the SDK now re-applies the last known subscribed codecs.

One frame per layer can still escape, because the encoders restart during the apply. This matches the JavaScript and Rust SDKs.
