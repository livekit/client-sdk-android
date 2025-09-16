package io.livekit.android.test.mock.e2ee

import io.livekit.android.e2ee.KeyProvider
import livekit.org.webrtc.FrameCryptorKeyProvider
import org.mockito.kotlin.mock

class NoopKeyProvider(override val rtcKeyProvider: FrameCryptorKeyProvider = mock<FrameCryptorKeyProvider>(), override var enableSharedKey: Boolean = true) : KeyProvider {
    override fun setSharedKey(key: String, keyIndex: Int?): Boolean {
        return true
    }

    override fun ratchetSharedKey(keyIndex: Int?): ByteArray {
        return ByteArray(0)
    }

    override fun exportSharedKey(keyIndex: Int?): ByteArray {
        return ByteArray(0)
    }

    override fun setKey(key: String, participantId: String?, keyIndex: Int?) {
    }

    override fun ratchetKey(participantId: String, keyIndex: Int?): ByteArray {
        return ByteArray(0)
    }

    override fun exportKey(participantId: String, keyIndex: Int?): ByteArray {
        return ByteArray(0)
    }

    override fun setSifTrailer(trailer: ByteArray) {
    }

    override fun getLatestKeyIndex(participantId: String): Int {
        return 0
    }
}
