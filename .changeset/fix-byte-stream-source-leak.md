---
"client-sdk-android": patch
---

Fixed file descriptor leak in ByteStreamSender where Source was not closed after reading.
