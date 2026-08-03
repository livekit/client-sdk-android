---
"client-sdk-android": patch
---

Fix: only latch WebRTC initialization after PeerConnectionFactory.initialize succeeds, so a failed native library load stays retryable and surfaces as a catchable exception instead of poisoning the process and crashing on the next LiveKit.create() call.
