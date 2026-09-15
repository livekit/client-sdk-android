---
"client-sdk-android": minor
---

Add data tracks, a named channel for streaming structured frames to a room.

Publish with `LocalParticipant.publishDataTrack` (or `withDataTrack` to scope a publication to a
block), then push frames with `LocalDataTrack.tryPush` or `send`. Subscribers find published tracks
through `RemoteParticipant.dataTracks` or the new `RoomEvent.DataTrackPublished` /
`DataTrackUnpublished` events, and call `RemoteDataTrack.subscribe` for a `DataTrackStream` of
incoming frames.

Frame formats are declared through `DataTrackPublishOptions` using `DataTrackFrameEncoding`
(protobuf, flatbuffer, CDR, ROS 1, CBOR, msgpack, JSON), and schema definitions can be shared
with `LocalParticipant.defineSchema` / `getSchema`. Frames are end-to-end encrypted when E2EE
is enabled on the room.

This adds a dependency on `io.livekit:livekit-uniffi-android`, containing the shared Rust core also
used by the other native SDKs.
