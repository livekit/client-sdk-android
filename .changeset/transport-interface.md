---
"client-sdk-android": patch
---

Move RTCEngine's peer connections behind an internal `Transport` interface. `DualPeerConnectionTransport` implements it for the existing publisher and subscriber peer connection pair and owns the decisions that depend on that arrangement: ICE candidate routing, answering server offers, which peer connections a resume waits on, and the sync state descriptions. No behavior changes.
