---
"client-sdk-android": minor
---

Data streams v2: Data streams are now backed by the shared Rust core (`livekit-ffi`), which adds the v2 wire format: a small, known-size payload is sent as a single packet with the content inline in the header, and payloads can be `deflate-raw` compressed. Both are negotiated per recipient, so rooms with older peers keep working.

- `ConnectOptions.clientProtocol` now defaults to `ClientProtocolVersion.DATA_STREAM_V2`.
- New `ClientCapability` enum and `Participant.capabilities`, mirroring the capabilities each peer's client advertises.
- New `RoomOptions.dataStreamOptions` / `Room.dataStreamOptions`, with `DataStreamOptions.maxPayloadByteLength` to cap the size of a reassembled incoming payload.
- New `compress` flag on `StreamTextOptions` and `StreamBytesOptions` (default `true`). Only applies to whole-payload sends such as `sendText`/`sendBytes`; incremental streams are never compressed.
- New `OutgoingDataStreamManager.sendBytes` convenience for sending a whole byte array.
- New `StreamException` types: `HeaderTooLargeException` and `PayloadTooLargeException` (both subclasses of `LengthExceededException`), and `InternalException`. `TerminatedException` gains a `reason`. An exhaustive `when` over `StreamException` needs a branch for the new cases.
- Incoming streams are held to the encryption type their header arrived with; a mismatching chunk or trailer fails the reader with `EncryptionTypeMismatch`.
