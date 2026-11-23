---
"client-sdk-android": minor
---

Add a `keySize` option to `BaseKeyProvider` (`KeyProviderKeySize.BITS_128` by default, or `BITS_256`) to derive AES-256-GCM frame keys for E2EE. All participants in a room must use the same size. Matches `KeyProviderOptions.keySize` in the JS and Swift SDKs.
