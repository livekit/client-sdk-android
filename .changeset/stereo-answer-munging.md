---
'livekit-android': patch
---

Negotiate stereo Opus on the subscriber answer: add `stereo=1` to the fmtp line for media sections where the server offer advertised `sprop-stereo=1`. Without this, a stereo track published by another participant is decoded as mono on Android. Mirrors client-sdk-js behavior.
