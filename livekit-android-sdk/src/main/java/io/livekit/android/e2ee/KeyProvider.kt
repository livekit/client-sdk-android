package io.livekit.android.e2ee

import org.webrtc.FrameCryptorFactory
import org.webrtc.FrameCryptorKeyProvider

class KeyInfo
constructor(var participantId: String, var keyIndex: Int, var key: String ) {
    override fun toString(): String {
        return "KeyInfo(participantId='$participantId', keyIndex=$keyIndex, key='$key')"
    }
}

 public interface KeyProvider {
    fun setKey(key: String, participantId: String?, keyIndex: Int  = 0)
    fun ratchetKey(participantId: String, index: Int): ByteArray

    val rtcKeyProvider: FrameCryptorKeyProvider

     var sharedKey: ByteArray?

     var enableSharedKey: Boolean
}

class BaseKeyProvider
constructor(ratchetSalt: String, uncryptedMagicBytes: String, ratchetWindowSize: Int, enableSharedKey: Boolean) :
    KeyProvider {
    override var sharedKey: ByteArray? = null
    var ratchetSalt: String
    var uncryptedMagicBytes: String
    var ratchetWindowSize: Int
    override var enableSharedKey: Boolean = false
    var keys: MutableMap<String, MutableMap<Int, String>> = mutableMapOf()
    override fun setKey(key: String, participantId: String?, keyIndex: Int) {
        if (enableSharedKey) {
            sharedKey = key.toByteArray();
            return;
        }

        var keyInfo = KeyInfo("", keyIndex, key);

        if (!keys.containsKey(keyInfo.participantId)) {
            keys[keyInfo.participantId] = mutableMapOf();
        }
        keys[keyInfo.participantId]!![keyInfo.keyIndex] = keyInfo.key;
        rtcKeyProvider.setKey(participantId, keyIndex, key.toByteArray())
    }

    override fun ratchetKey(participantId: String, index: Int): ByteArray {
        return rtcKeyProvider.ratchetKey(participantId, index)
    }

    override lateinit var rtcKeyProvider: FrameCryptorKeyProvider

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
}
