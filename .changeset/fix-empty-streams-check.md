---
"client-sdk-android": patch
---

Fixed impossible empty streams check in Room.onAddTrack that could crash if WebRTC called onAddTrack with an empty streams array.