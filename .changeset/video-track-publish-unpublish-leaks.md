---
"client-sdk-android": patch
---

Fix native memory leaks on video track publish/unpublish cycles (#521). `LocalVideoTrack.dispose()` now disposes its backing `VideoSource`, which was previously left undisposed and leaked for the lifetime of the process (only the track and capturer were released). Unpublishing a video track now also stops its `RtpTransceiver`, along with any extra transceivers added for backup codecs; since a new transceiver is created on every publish, removing the track from its sender alone left them retained until the connection closed.
