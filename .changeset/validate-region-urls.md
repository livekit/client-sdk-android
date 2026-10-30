---
"client-sdk-android": patch
---

Validate region URLs before failing over to them. Regions returned by `/settings/regions` or reported by the server were previously used as-is. Each URL must now name a LiveKit Cloud host and use an encrypted scheme (`wss`/`https`); entries that do not are logged and dropped.
