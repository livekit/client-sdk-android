---
"client-sdk-android": patch
---

Seed the bandwidth estimator with `x-google-start-bitrate` for all video codecs, not just SVC, so published video reaches its target quality in the first second or two instead of ramping from ~300 kbps over 5-15 seconds. The hint is 90% of the track's target bitrate, capped at 1 Mbps for camera tracks (screen shares are exempt, since they are published at high bitrates for text legibility) and skipped below a 300 kbps target, where seeding high costs more than it gains.

Because libwebrtc applies these codec fmtp parameters to the whole peer connection rather than the m-section carrying them, the SDK now writes a single connection-level value to every video m-section, once per publisher connection. Re-seeding a converged estimator is avoided: the value persists in libwebrtc's bitrate configurator and is automatically re-applied on network route changes, and a full reconnect builds a new peer connection and seeds it again.

**Behavior change:** the SDK no longer writes `x-google-max-bitrate` into SDP. That value was promoted to a ceiling on total send bandwidth for the entire connection, so a camera publication could throttle a concurrent screen share. Per-track and per-layer limits continue to be enforced through `RtpParameters.Encoding.maxBitrateBps`, which is correctly scoped per encoding. Applications that relied on the SDP value as a connection-wide cap should set encoding bitrates instead. This matches client-sdk-js and the Rust SDK, neither of which writes it.

`TrackBitrateInfo` and `TrackBitrateInfoKey` are now `internal`. They were never intended as public API (both were `@suppress`ed and only reachable through a `@VisibleForTesting` helper) and were public only to be visible from the test module, which is no longer necessary.
