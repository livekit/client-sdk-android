---
"client-sdk-android": patch
---

Update libwebrtc to 150.7871.01 and turn on WARP (draft-uberti-tsvwg-warp) by default.

Two connection-setup accelerations are now enabled out of the box: the `WebRTC-IceHandshakeDtls`
field trial, which carries the DTLS handshake inside the ICE STUN binding exchange so DTLS and ICE
negotiate in parallel, and `RTCConfiguration.enableSctpSnap`, which puts the SCTP INIT parameters
in the SDP so a data channel skips SCTP's cookie exchange. Both are negotiated with the server and
fall back to the plain DTLS/SCTP setup when it doesn't support them.
