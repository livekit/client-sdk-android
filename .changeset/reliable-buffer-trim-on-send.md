---
"client-sdk-android": patch
---

Fix memory growing without limit while a room stays connected. Reliable data packets are kept in a buffer so they can be resent after a resume, but the buffer was only trimmed when the data channel's buffered amount changed. Small packets, such as the client metrics sent twice a second by default, never change it, so every packet was kept until the room disconnected, about 2 MB an hour per room. The buffer is now trimmed after each reliable send, as in the JavaScript SDK (#1063).
