package io.livekit.android.e2ee

import livekit.LivekitModels.Encryption

class E2EEOptions
constructor(keyProvider: KeyProvider)  {
    var keyProvider: KeyProvider
    var encryptionType: Encryption.Type = Encryption.Type.NONE
    init {
        this.keyProvider = keyProvider
    }
}
