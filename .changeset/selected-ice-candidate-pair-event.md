---
"client-sdk-android": minor
---

Add `RoomEvent.SelectedIceCandidatePairChanged`, emitted whenever the publisher or subscriber peer connection selects a new ICE candidate pair, including the initial selection and selections after reconnecting. The event carries a `SelectedIceCandidatePair` with the connection target, the parsed local and remote candidates (type, protocol, address, port, priority, adapter type, server URL and the raw candidate line), whether the path is relayed through TURN, and libwebrtc's change reason and estimated disconnection time. This makes network handovers and direct/relay path switches observable without polling RTC stats.
