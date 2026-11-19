---
"client-sdk-android": minor
---

Add frame metadata for video tracks. A publisher can send a capture timestamp, a frame ID and its own user data with each video frame, carried in a trailer after the encoded frame, and subscribers look them up by RTP timestamp. The format is the same as the JavaScript and Rust SDKs, so metadata works between them, with or without end-to-end encryption.

To publish, set `frameMetadata` on `VideoTrackPublishOptions` or `VideoTrackPublishDefaults`. By default the SDK sends the frame's capture time as wall-clock time and a per-track frame ID; supply your own values with `LocalVideoTrack.frameMetadataProvider`, or on the frames your video processor emits. To read them, call `RemoteVideoTrack.lookupFrameMetadata(frame.rtpTimestamp)` from a video sink. `TrackPublication.frameMetadataFeatures` lists the fields a track's publisher declared, as `FrameMetadataFeature` values. `FrameMetadata`, `FrameMetadataFeature`, `FrameMetadataProvider`, `frameMetadataProvider`, `frameMetadataFeatures` and `lookupFrameMetadata` are in beta and need an opt-in to `@Beta`; the publish options don't, so `copy()` on the publish option classes works as before. User data isn't encrypted under end-to-end encryption, so the server can read it.

`E2EEManager.addPublishedTrack`, `addSubscribedTrack`, `removePublishedTrack` and `removeSubscribedTrack` are now internal. The room calls them for you, and they no longer install the cryptor themselves, so an app calling them directly would have sent unencrypted media.

`LocalVideoTrack.restartTrack` now moves backup codec senders to the new track as well. Before, backup codec video stopped after a restart.
