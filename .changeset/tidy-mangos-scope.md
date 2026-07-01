---
"client-sdk-android": patch
---

Scoped the protobuf consumer keep rule to the SDK's generated messages and the well-known types they embed, instead of every GeneratedMessageLite subclass in the consuming app.
