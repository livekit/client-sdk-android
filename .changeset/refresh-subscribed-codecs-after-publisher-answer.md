---
"client-sdk-android": patch
---

Keep dynacast-paused simulcast layers paused across publisher renegotiations. Previously a paused layer started to upload again after any renegotiation of the publisher connection. Publishing a track, unpublishing one, a codec change, and an ICE restart all caused this. With servers before v1.13.8, the layer stayed on until the next subscribed-quality change. Newer servers restate the pause a few seconds after the answer (livekit/livekit#4907), so there it stayed on for that window.

The server's answer to a publisher offer no longer carries the RFC 8853 pause markers. Applying that answer re-enables every layer the client paused. Directly after it applies a publisher answer, the SDK now re-applies the last known subscribed codecs.

One frame per layer can still escape, because the encoders restart during the apply. This matches the JavaScript and Rust SDKs.
