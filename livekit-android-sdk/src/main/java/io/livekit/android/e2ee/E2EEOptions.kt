package io.livekit.android.e2ee

import livekit.LivekitModels.Encryption

var defaultRatchetSalt = "LKFrameEncryptionKey";
var defaultMagicBytes = "LK-ROCKS";
var defaultRatchetWindowSize = 16;

class E2EEOptions
constructor(keyProvider: KeyProvider = BaseKeyProvider(
    defaultRatchetSalt,
    defaultMagicBytes,
    defaultRatchetWindowSize,
    true,
), encryptionType: Encryption.Type = Encryption.Type.GCM) {
    var keyProvider: KeyProvider
    var encryptionType: Encryption.Type = Encryption.Type.NONE
    init {
        this.keyProvider = keyProvider
        this.encryptionType = encryptionType
    }
}
