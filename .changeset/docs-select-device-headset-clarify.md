---
"client-sdk-android": patch
---

docs(audio): clarify that `AudioSwitchHandler.selectDevice()` is sticky and overrides `preferredDeviceList`. Document that callers who only need a different priority order should set `preferredDeviceList` instead, and that `selectDevice(null)` clears a sticky selection.
