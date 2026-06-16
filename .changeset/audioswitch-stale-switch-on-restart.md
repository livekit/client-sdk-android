---
"client-sdk-android": patch
---

Fix audio output getting stuck on the earpiece after reconnecting on a reused `Room` (Android 12+). `AudioSwitchHandler.stop()` now clears its `audioSwitch` reference synchronously (and the field is `@Volatile`) so a subsequent `start()` reliably observes the teardown and re-creates the switch, instead of racing the posted teardown runnable and reusing a stale, already-stopped switch.
