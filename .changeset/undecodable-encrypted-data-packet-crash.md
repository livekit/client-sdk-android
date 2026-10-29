---
"client-sdk-android": patch
---

Fix a crash when an end-to-end encrypted data packet decrypts to bytes that are not a valid payload. The packet is now logged and dropped.
