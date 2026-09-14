---
"client-sdk-android": patch
---

Fix the subscriber silently buffering remote ICE candidates after a resume

A soft reconnect put the subscriber into an ice restart state, but only `setRemoteDescription`
clears that and the server re-offers the subscriber only when the reconnect moved the participant
to another node. After an ordinary resume no offer arrives, so the flag stayed set for the life of
the transport and every later remote candidate was queued instead of applied, leaving the
subscriber unable to adopt any new path the server proposed. The subscriber no longer enters that
state: the server does not send candidates ahead of the offer that introduces them, so queueing
them gains nothing. Matches the same fix in client-sdk-js.
