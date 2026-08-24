---
"client-sdk-android": patch
---

Fix CachingTokenSource treating a JWT with no exp as still valid, so a token that never expires stays cached forever. Require exp; keep nbf optional.
