---
"client-sdk-android": patch
---

Fixed ProGuard/R8 minification crash in release builds when using AgentAttributes. Added @Keep annotations to Klaxon-based data classes and updated ProGuard rules to prevent obfuscation of reflection-based JSON parsing classes. #808