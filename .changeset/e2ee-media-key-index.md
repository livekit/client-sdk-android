---
"client-sdk-android": patch
---

Fix `getLatestKeyIndex` always returning 0 in shared-key mode and add `E2EEManager.setKeyIndex` to re-point existing frame cryptors after a key rotation, enabling nonzero media `kid` stamps for key-rotation use cases (e.g. MLS epoch transitions).
