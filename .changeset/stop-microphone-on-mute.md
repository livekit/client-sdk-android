---
"client-sdk-android": minor
---

Add `LocalAudioTrackOptions.stopMicrophoneOnMute`, which stops microphone capture while the audio track is muted. This releases the microphone for other apps and turns off the OS microphone-in-use indicator, without unpublishing the track. Capture restarts automatically on unmute.

Also fix local track publications leaking their jobs on every full reconnect, which left the audio feature collectors of the old publications running and sending feature updates for stale track sids.
