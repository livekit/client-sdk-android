---
"client-sdk-android": patch
---

Fix crashes when a data channel packet, a decrypted end-to-end encrypted payload, or a signal message cannot be parsed. The message is now logged and dropped.
