---
"client-sdk-android": patch
---

Use source-specific default video degradation preferences: camera tracks default to maintaining framerate, screen share tracks default to maintaining resolution, and other video sources default to balanced. This matches client-sdk-js. Video tracks published with an explicit `source` other than camera or screen share now use balanced rather than WebRTC's implicit choice; set `degradationPreference` on the publish options to override.

The resolved preference is now also applied to the backup codec's sender. Previously only the primary encoder was configured and the backup encoder let libwebrtc derive a preference implicitly, so the two encoders could adapt along different axes off the same video source.
