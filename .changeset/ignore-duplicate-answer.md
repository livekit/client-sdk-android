---
"client-sdk-android": patch
---

Ignore a duplicated server answer. `PeerConnectionTransport.setRemoteDescription` rejected answers carrying an *older* offer id, but a second copy of the answer for the *current* offer passed straight through to `setRemoteDescription`. A proxy duplicating the signal message, or a server retry, therefore applied the same answer twice — the second time with no local offer left to answer, which is an invalid transition and left the transport reporting an error on a renegotiation that had in fact succeeded.

The transport now tracks the offer whose answer was applied, and the offer whose answer is currently being applied, and drops a repeat of either. A retry after a genuinely failed apply is still accepted, and the legacy unnumbered-answer path falls back to the signaling state.

This matches the JavaScript SDK (livekit/client-sdk-js#2114).
