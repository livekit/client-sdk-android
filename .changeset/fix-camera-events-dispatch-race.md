---
"client-sdk-android": patch
---

Fixed data race in `CameraEventsDispatchHandler` by using `CopyOnWriteArraySet` for thread-safe iteration.