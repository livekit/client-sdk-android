package io.livekit.android.e2ee

enum class EncryptionType {
    NONE,
    GCM,
    CUSTOM,
}

class E2EEOptions
constructor(keyProvider: KeyProvider)  {
    var keyProvider: KeyProvider
    var encryptionType: EncryptionType = EncryptionType.GCM
    init {
        this.keyProvider = keyProvider
    }
}
