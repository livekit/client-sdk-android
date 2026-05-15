---
"client-sdk-android": patch
---

docs(audio): clarify that `AudioSwitchHandler.selectDevice()` overrides the automatic selection driven by `preferredDeviceList` and add a recommended sample for implementing a speakerphone toggle that still respects wired/Bluetooth headsets. Also note the `BLUETOOTH_CONNECT` (Android 12+) runtime permission requirement for Bluetooth devices to appear in `availableAudioDevices`.
