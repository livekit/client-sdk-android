---
"client-sdk-android": patch
---

Media frame cryptors now follow the key provider's current key index, matching the JavaScript and Swift SDKs. `BaseKeyProvider` implements the new `KeyIndexObservable` contract: installing a shared key moves the current index, `setCurrentKeyIndex` re-points live senders, and `getLatestKeyIndex` returns the current index. Custom `KeyProvider` implementations can opt into live re-pointing by implementing `KeyIndexObservable`. Behavior change: in non-shared key mode `setKey` no longer moves the sender index — the key may belong to a remote participant — use `setCurrentKeyIndex` to switch the local one.
