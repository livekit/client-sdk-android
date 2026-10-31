---
"client-sdk-android": patch
---

Fix caching token source incorrectly calculating the expiration. It now treats a token within the tolerance (default: 60 seconds) as already expired and fetches a fresh one, instead of keeping it past its expiry.