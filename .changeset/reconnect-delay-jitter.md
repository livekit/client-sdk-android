---
"client-sdk-android": patch
---

Add proportional jitter to the default reconnect delays. `DefaultReconnectPolicy` returned a fixed delay for each retry count, so every client disconnected by the same event — a server restart, a region failover, a dropped uplink — waited exactly 100ms, then 300ms, then 300ms, and so on, in lockstep. They retried as one herd and could keep re-synchronising across attempts.

Each delay is now scaled by a random factor between 0.5 and 1.5, spreading clients further apart with every retry. A first retry whose configured delay is zero instead draws from a fixed 0–500ms window, since there is no base delay to scale; a zero at any later index still means retry immediately.

This matches the JavaScript SDK (livekit/client-sdk-js#2124).
