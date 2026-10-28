---
"client-sdk-android": minor
---

Client telemetry through the shared Rust core: each Room reports its connect, reconnect, publish and subscribe spans, RTC statistics, SDK warnings and errors and device state to its LiveKit Cloud project when the token carries the observability grant; apps can add `Room.emitTelemetryEvent(name, attributes)` and `Room.setTelemetryAttribute(key, value)`, and opt out with `LiveKit.disableTelemetry()`.
