---
"client-sdk-android": patch
---

Update libwebrtc to 150.7871.04, which removes the AAA Certificate Services root from the TURN/TLS trust anchors so certificates from the compromised eMudhra intermediates are rejected.

This version also passes the TLS hostname to the Android platform certificate verifier, so the fallback to the OS trust store no longer fails when the app's `network_security_config` has a `domain-config`.
