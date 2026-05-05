---
"client-sdk-android": patch
---

Fixed RTCEngine.addTrack leaking pendingTrackResolvers entries on timeout or caller cancellation, which previously caused subsequent publishes of the same track to fail with DuplicateTrackException until the connection was torn down.
