---
"client-sdk-android": patch
---

Fixed non-local return in `onConnectionQuality` that caused lost connection quality updates for remaining participants when one participant was not found in the list.
