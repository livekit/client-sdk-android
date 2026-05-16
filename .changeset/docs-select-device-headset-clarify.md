---
"client-sdk-android": patch
---

docs(audio): clarify that `AudioSwitchHandler.selectDevice()` is sticky and overrides the automatic selection driven by `preferredDeviceList`. Document that callers who only need a different priority order (e.g. prefer Speakerphone over Earpiece as the fallback when no headset is present) should set `preferredDeviceList` instead, and that `selectDevice(null)` returns to fully automatic selection.
