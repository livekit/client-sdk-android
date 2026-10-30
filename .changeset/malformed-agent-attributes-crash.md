---
"client-sdk-android": patch
---

Fix a crash when a participant's `lk.agent.inputs` or `lk.agent.outputs` attribute is not a valid JSON array. The attribute is now logged as a warning and the participant's agent attributes are left unchanged.
