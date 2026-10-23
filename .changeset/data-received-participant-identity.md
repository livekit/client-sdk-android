---
"client-sdk-android": patch
---

Expose `participantIdentity` on `RoomEvent.DataReceived`. The event's `participant` is nullable and is null whenever the sender is not in the room — data sent through the Server API, or by a participant that has already left. In that case the receiver had no way to tell who sent the data, because the engine dropped the identity before building the event: `onUserPacket` was handed only the inner `UserPacket`, while the authoritative identity lives on the enclosing `DataPacket`.

The internal engine listener now forwards that identity, and it is reported on every `DataReceived` event whether or not the sender resolves to a `RemoteParticipant`.

This matches the JavaScript SDK (livekit/client-sdk-js#2092).
