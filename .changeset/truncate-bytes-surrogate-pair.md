---
"client-sdk-android": patch
---

Stop `truncateBytes` from splitting a surrogate pair. The helper binary searches for the longest prefix that fits a byte budget, counting in UTF-16 code units. When the budget fell between the two halves of a surrogate pair, it cut the pair in half and returned a string ending in an unpaired high surrogate, which UTF-8 encodes as a replacement character. An RPC error message or data payload trimmed at such a boundary therefore reached the remote peer corrupted. The cut point now steps back one code unit when it lands on a high surrogate.

This matches the JavaScript SDK (livekit/client-sdk-js#2126).
