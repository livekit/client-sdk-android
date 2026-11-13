---
"client-sdk-android": patch
---

Bundle the publisher offer with the `/rtc/v1` join request when connecting with `ConnectOptions.singlePeerConnection`. The publisher peer connection, its data channels and the first offer are created before the signal connection opens, and the server answers that offer right after the join response, which saves one offer/answer round trip on connect.
