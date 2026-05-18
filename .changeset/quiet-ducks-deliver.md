---
"client-sdk-android": patch
---

Fixed silent loss of reliable data when DataChannel.send returned false and when buffered items were replayed across multiple resumes.
