package io.livekit.android.e2ee

import org.webrtc.FrameCryptorFactory
import org.webrtc.FrameCryptorKeyProvider

class KeyProvider
constructor(ratchetSalt: String, uncryptedMagicBytes: String, ratchetWindowSize: Int, enableSharedKey: Boolean)  {
    var sharedKey: ByteArray? = null
    var ratchetSalt: String
    var uncryptedMagicBytes: String
    var ratchetWindowSize: Int
    var enableSharedKey: Boolean
    var rtcKeyProvider: FrameCryptorKeyProvider? = null

    init {
        this.ratchetSalt = ratchetSalt
        this.uncryptedMagicBytes = uncryptedMagicBytes
        this.ratchetWindowSize = ratchetWindowSize
        this.enableSharedKey = enableSharedKey
        this.rtcKeyProvider = FrameCryptorFactory.createFrameCryptorKeyProvider(
            enableSharedKey,
            ratchetSalt.toByteArray(),
            ratchetWindowSize,
            uncryptedMagicBytes.toByteArray(),
        )
    }

    fun setSharedKey(sharedKey: String) {
        this.sharedKey = sharedKey.toByteArray()
    }
}
