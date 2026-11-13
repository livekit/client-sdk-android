---
"client-sdk-android": patch
---

Fix the subscriber silently buffering remote ICE candidates after a resume

A soft reconnect put the subscriber into an ice restart state, but only `setRemoteDescription`
clears that and the server re-offers the subscriber only when the reconnect moved the participant
to another node. After an ordinary resume no offer arrives, so the state stayed set for the life of
the transport and every later remote candidate was queued instead of applied, leaving the
subscriber unable to adopt any new path the server proposed.

Candidates now wait on the description they belong to rather than on the reconnect. `onServerOffer`
says a description is coming before it schedules the work that applies it, so a candidate that
arrives in between is held rather than tried against the description being replaced. Each wait is
owned by what answers it, an ice restart offer by its own offer id and a server offer by its own
turn, and it ends when that attempt ends whether the description lands, is refused, or answers an
offer that has since been superseded.
