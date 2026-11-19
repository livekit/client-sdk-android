---
"client-sdk-android": patch
---

`performRpc` now fails as soon as the room disconnects, rather than staying suspended until its response timeout.

Room cleanup reports every remote participant as disconnected, which already rejected calls addressed to a participant the room knew about. A call to an identity that never appeared in the participant list — an agent that has not joined yet, say — was in no such loop, so it stayed pending for the full `responseTimeout` after the room had gone. The caller's coroutine is now resumed with `RECIPIENT_DISCONNECTED` instead.

This matches the Rust SDK's `fail_all_pending` and the Swift SDK's `handleAllPendingDisconnected` (livekit/client-sdk-js#2251).
