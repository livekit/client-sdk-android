---
"client-sdk-android": patch
---

Fix `ScreenAudioCapturer` crashing the audio thread when its `MediaProjection` is revoked (for example via the system "stop sharing" chip) before the first microphone buffer arrives. `initAudioRecord` now returns false when the `AudioRecord` cannot be created instead of throwing inside WebRTC's audio record thread, where an unhandled exception kills the process; only `startRecording()` was guarded before. `AudioRecord.read` failures are also handled now: the return value was ignored, so once the projection died the buffer's stale contents (the last captured frame) were mixed into the microphone track on every callback, an audible loop until the callback was detached. On a read error the capturer releases its `AudioRecord` and degrades to mic-only audio.

`releaseAudioResources` is also safe to call while `initAudioRecord` is still running. It runs on the app's thread while init runs on the audio record thread, and it used to observe a null `audioRecord` and do nothing, so the recorder that init went on to publish stayed running until finalization. Leaked recorders hold the playback capture input open, and later capture attempts fail once enough of them accumulate.
