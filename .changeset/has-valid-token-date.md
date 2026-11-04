---
"client-sdk-android": patch
---

`hasValidToken` now validates against its `date` parameter. Previously the parameter was ignored and the current time was always used.
