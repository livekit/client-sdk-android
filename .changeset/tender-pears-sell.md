---
"client-sdk-android": patch
---

Improved handling of track publication failures by introducing a new TrackPublicationFailed event and fixing a broken state issue where the track remained active but inaccessible, causing the microphone or camera to stay on without a published track and leading to unreliable republishing.
