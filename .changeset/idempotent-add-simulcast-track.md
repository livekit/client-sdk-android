---
"client-sdk-android": patch
---

Fix crash when a duplicate SubscribedQualityUpdate requests a backup codec that is already added. addSimulcastTrack now skips the duplicate instead of throwing IllegalStateException ("VP8 already added!"), matching the JS SDK behavior.
