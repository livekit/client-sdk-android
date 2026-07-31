---
"client-sdk-android": patch
---

Fixed full reconnect republish racing concurrent publishes of the same track, which could leave the mic published but silent or silently unpublished.
