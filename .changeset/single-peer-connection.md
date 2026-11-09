---
"client-sdk-android": minor
---

Add `ConnectOptions.singlePeerConnection`, which joins through the `/rtc/v1` signal path and publishes and receives on one peer connection instead of a publisher and a subscriber. The client makes every offer; the server's media sections requirement adds receive-only transceivers, and the answer's mid-to-track mapping resolves received tracks. A server without `/rtc/v1` answers that handshake with 404, in which case the join retries on the legacy path with dual peer connections and later reconnects stay there. The option is off by default.
