---
"client-sdk-android": patch
---

Fix `ScreenCaptureService` staying bound when screen share setup is cancelled. `ScreenCaptureConnection` recorded a binding only once `onServiceConnected` arrived, so a coroutine cancelled before `connect()` returned left the `ServiceConnection` registered, and `BIND_AUTO_CREATE` kept the service alive for the lifetime of the context. `LocalParticipant.setScreenShareEnabled` awaits the bind internally and abandons the track it just created if cancelled, so nothing reached `stop()` on that path. A cancelled connect now releases the binding itself once no caller is left waiting on it, and `stop()` unbinds on the same wider condition. Callers stay tracked until `connect()` actually returns, so a cancellation landing after the service connected but before the caller resumed releases the binding too. This also covers the documented case where `bindService` leaves a connection registered while reporting failure or throwing.

Two paths that could leave `connect()` suspended forever are fixed as well. `stop()` racing a connect no longer strands the caller, since requesting the bind and registering the waiter now happen under one lock, and a failed `bindService` no longer leaves the state claiming a bind is in flight for the next caller to wait on.
