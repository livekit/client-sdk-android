---
"client-sdk-android": patch
---

Fixed local track publications leaking their jobs on every full reconnect, which left the audio feature collectors of the old publications running and sending feature updates for stale track sids.
