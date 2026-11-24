# Logging the selected ICE candidate pair

Every time libwebrtc's ICE agent switches the network path that carries media, the SDK now logs the **full
change event and both candidates**, and emits a `RoomEvent` the application can consume.

Implemented in this repo — see [What was changed](#what-was-changed).

## What you get

For every switch, on either peer connection (publisher = uplink, subscriber = downlink — separate ICE
sessions):

| Signal | Use |
| --- | --- |
| candidate `type` (`host` / `srflx` / `prflx` / `relay`) | direct path or TURN relay |
| `address:port`, `protocol`, `tcpType` | the actual 5-tuple carrying media |
| `adapterType` (`WIFI`, `CELLULAR`, `VPN`, `ETHERNET`, …) | detect wifi↔cellular handover |
| `serverUrl` | which STUN/TURN server produced the candidate |
| `priority` + decoded `typePref` / `localPref` / `componentPref` | why ICE ranked this candidate where it did |
| `raddr` / `rport` | the address behind the NAT |
| `foundation`, `component`, `generation`, `ufrag`, `network-id`, `network-cost` | full candidate line, nothing dropped |
| `reason` | libwebrtc's own explanation for the switch |
| `estimatedDisconnectedTimeMs` | how long media was interrupted before the new pair took over |
| `lastDataReceivedMs` | monotonic timestamp of last data on the new pair |

## Log output

The SDK logs at INFO from the observers, so it only needs `LiveKit.loggingLevel`:

```kotlin
LiveKit.loggingLevel = LoggingLevel.INFO
```

```
ICE selected candidate pair changed [PUBLISHER]
  relayed:                     false
  reason:                      remote candidate generation maybe old
  lastDataReceivedMs:          1394857
  estimatedDisconnectedTimeMs: 0
  local:
    type:       srflx  protocol: udp  tcpType: -
    address:    5.1.2.3:41234
    related:    192.168.1.5:41234
    foundation: 842163049  component: 1 (RTP)
    priority:   1677729535 (typePref=100 localPref=65535 componentPref=255)
    adapter:    WIFI  serverUrl: stun:stun.l.google.com:19302
    mid:        0 (mLineIndex=0)
    extra:      generation=0, ufrag=Kd2R, network-id=2, network-cost=10
    sdp:        candidate:842163049 1 udp 1677729535 5.1.2.3 41234 typ srflx raddr 192.168.1.5 rport 41234 generation 0 ufrag Kd2R network-id 2 network-cost 10
  remote:
    type:       host  protocol: udp  tcpType: -
    address:    34.10.0.9:7881
    related:    -
    foundation: 3162092614  component: 1 (RTP)
    priority:   2130706431 (typePref=126 localPref=65535 componentPref=255)
    adapter:    UNKNOWN  serverUrl: -
    mid:        0 (mLineIndex=0)
    extra:      generation=0, ufrag=8fJx
    sdp:        candidate:3162092614 1 udp 2130706431 34.10.0.9 7881 typ host generation 0 ufrag 8fJx
```

Filter it:

```bash
adb logcat | grep -A22 "ICE selected candidate pair changed"
```

## Consuming it in the app

```kotlin
lifecycleScope.launch {
    room.events.collect { event ->
        if (event !is RoomEvent.SelectedIceCandidatePairChanged) return@collect
        val pair = event.pair

        // One-liner, e.g. "[PUBLISHER] srflx/udp 5.1.2.3:41234 via WIFI -> host/udp 34.10.0.9:7881
        // relayed=false gap=0ms reason=..."
        Log.i("IceWatch", pair.summary())

        // Or the same block the SDK logs.
        Log.i("IceWatch", pair.describe())

        if (pair.isRelayed) {
            analytics.track("ice_relay_fallback", mapOf("target" to pair.target.name))
        }
        if (pair.estimatedDisconnectedTimeMs > 0) {
            analytics.track("ice_media_gap_ms", mapOf("ms" to pair.estimatedDisconnectedTimeMs))
        }
        if (pair.local.adapterType == PeerConnection.AdapterType.CELLULAR) {
            // Uplink is on mobile data — consider dropping publish quality.
        }
    }
}
```

Field-level access, everything already parsed:

```kotlin
pair.target                    // IceTarget.PUBLISHER / SUBSCRIBER
pair.local.type                // "host" | "srflx" | "prflx" | "relay"
pair.local.address             // "5.1.2.3"
pair.local.port                // 41234
pair.local.protocol            // "udp" | "tcp"
pair.local.tcpType             // "active" | "passive" | "so" | null
pair.local.relatedAddress      // address behind the NAT, or null
pair.local.priority            // 1677729535
pair.local.typePreference      // 100
pair.local.adapterType         // PeerConnection.AdapterType.WIFI
pair.local.serverUrl           // "stun:stun.l.google.com:19302"
pair.local.attributes["ufrag"] // any remaining key/value on the candidate line
pair.local.raw                 // the untouched a=candidate: line
```

## What was changed

| File | Change |
| --- | --- |
| `io/livekit/android/room/SelectedIceCandidatePair.kt` | **new** — `IceTarget`, `IceCandidateInfo`, `SelectedIceCandidatePair`, candidate-line parser, `describe()` / `summary()` |
| `io/livekit/android/room/PublisherTransportObserver.kt` | filled the empty `onSelectedCandidatePairChanged`, added `selectedCandidatePairListener` |
| `io/livekit/android/room/SubscriberTransportObserver.kt` | same, tagged `SUBSCRIBER` |
| `io/livekit/android/room/RTCEngine.kt` | wires both observers into `Listener.onSelectedIceCandidatePairChanged` |
| `io/livekit/android/events/RoomEvent.kt` | new `RoomEvent.SelectedIceCandidatePairChanged` |
| `io/livekit/android/room/Room.kt` | overrides the engine callback, posts the room event |

Key parts:

```kotlin
// PublisherTransportObserver.kt / SubscriberTransportObserver.kt
override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent?) {
    val changeEvent = event ?: return
    executeOnRTCThread(rtcThreadToken) {
        val pair = changeEvent.toSelectedIceCandidatePair(IceTarget.PUBLISHER)
        LKLog.i { pair.describe() }
        selectedCandidatePairListener?.invoke(pair)
    }
}
```

```kotlin
// RTCEngine.kt
private val publisherObserver = PublisherTransportObserver(this, client, rtcThreadToken).apply {
    selectedCandidatePairListener = { pair -> listener?.onSelectedIceCandidatePairChanged(pair) }
}
```

```kotlin
// Room.kt
override fun onSelectedIceCandidatePairChanged(pair: SelectedIceCandidatePair) {
    // Arrives on the RTC thread, and coroutineScope may not be initialized yet, so post without a scope.
    eventBus.tryPostEvent(RoomEvent.SelectedIceCandidatePairChanged(this, pair))
}
```

`RTCEngine.Listener.onSelectedIceCandidatePairChanged` has a default empty body, so existing implementers and
test fakes are unaffected.

## Behaviour notes

- **Fires per peer connection.** Publisher and subscriber switch independently — always read `pair.target`.
- **Fires on first selection too**, with `estimatedDisconnectedTimeMs == 0`. That is the "we are connected over
  this path" event.
- **Runs on the RTC thread.** Do no blocking or UI work in a `room.events` collector that handles it — the
  event is delivered through a `SharedFlow`, so collect on your own dispatcher.
- **A full reconnect** (`RTCEngine.kt:625`) recreates both peer connections, so a fresh first-selection event
  follows it.
- **`reason` is free-form** libwebrtc text; wording changes between versions. Log it, do not branch on it.
- **Parsing never throws.** Unknown or malformed candidate lines yield null fields and the raw line is always
  kept in `IceCandidateInfo.raw`.

## Testing

1. **Handover** — join on wifi, disable wifi with mobile data on. Expect events on both targets, with
   `estimatedDisconnectedTimeMs > 0` and `adapterType` changing to `CELLULAR`.
2. **Force relay** — a server-side `forceRelay` client configuration sets `iceTransportsType = RELAY`
   (`RTCEngine.kt:1034`); every selection then reports `relayed: true`.
3. **Cross-check with native logs**:

   ```kotlin
   LiveKit.enableWebRTCLogging = true
   LiveKit.loggingLevel = LoggingLevel.VERBOSE
   ```

   ```bash
   adb logcat | grep -iE "p2p_transport_channel|selected connection|ICE selected candidate pair"
   ```

## Snapshot instead of events

For "what path am I on right now", the existing stats API answers without waiting for a change:

```kotlin
room.getPublisherRTCStats { report ->
    val stats = report.statsMap
    val pair = (stats.values.firstOrNull { it.type == "transport" }
        ?.members?.get("selectedCandidatePairId") as? String)?.let { stats[it] }
        ?: stats.values.firstOrNull { it.type == "candidate-pair" && it.members["nominated"] == true }
    Log.i("IceWatch", "pair=${pair?.members}")
}
```

## Build note

Building this module requires the `protocol` git submodule (it was uninitialized):

```bash
git submodule update --init --recursive
```

Without it, `generateDebugProto` reports `NO-SOURCE` and Kotlin compilation fails with the unhelpful
`e: Could not load module <Error module>`.
