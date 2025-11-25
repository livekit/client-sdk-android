---
"client-sdk-android": patch
---

Fixed `ConcurrentModificationException` in `LocalAudioTrack.dispose()` when sinks are registered.