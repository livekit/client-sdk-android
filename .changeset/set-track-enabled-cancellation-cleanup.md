---
"client-sdk-android": patch
---

Fixed setTrackEnabled leaking track resources when cancelled before the track is published, including the sender negotiated for a failed publish, and LocalScreencastVideoTrack leaking its SurfaceTextureHelper on dispose.
