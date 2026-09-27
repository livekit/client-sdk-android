---
"client-sdk-android": patch
---

Fixed race condition in `PeerConnectionTransport.trackBitrates` by ensuring writes happen on the RTC thread.