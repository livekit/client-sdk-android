/*
 * Copyright 2023-2024 LiveKit, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.livekit.android.e2ee

import io.livekit.android.util.LKLog
import livekit.org.webrtc.FrameCryptorFactory
import livekit.org.webrtc.FrameCryptorKeyProvider

class KeyInfo
constructor(var participantId: String, var keyIndex: Int, var key: String) {
    override fun toString(): String {
        return "KeyInfo(participantId='$participantId', keyIndex=$keyIndex)"
    }
}

public interface KeyProvider {
    fun setSharedKey(key: String, keyIndex: Int? = 0): Boolean
    fun ratchetSharedKey(keyIndex: Int? = 0): ByteArray
    fun exportSharedKey(keyIndex: Int? = 0): ByteArray
    fun setKey(key: String, participantId: String?, keyIndex: Int? = 0)
    fun ratchetKey(participantId: String, keyIndex: Int? = 0): ByteArray
    fun exportKey(participantId: String, keyIndex: Int? = 0): ByteArray
    fun setSifTrailer(trailer: ByteArray)

    val rtcKeyProvider: FrameCryptorKeyProvider

    var enableSharedKey: Boolean
}

class BaseKeyProvider
constructor(
    private var ratchetSalt: String = defaultRatchetSalt,
    private var uncryptedMagicBytes: String = defaultMagicBytes,
    private var ratchetWindowSize: Int = defaultRatchetWindowSize,
    override var enableSharedKey: Boolean = true,
    private var failureTolerance: Int = defaultFailureTolerance,
    private var keyRingSize: Int = defaultKeyRingSize,
    private var discardFrameWhenCryptorNotReady: Boolean = defaultDiscardFrameWhenCryptorNotReady,
) : KeyProvider {
    private var keys: MutableMap<String, MutableMap<Int, String>> = mutableMapOf()
    override fun setSharedKey(key: String, keyIndex: Int?): Boolean {
        return rtcKeyProvider.setSharedKey(keyIndex ?: 0, key.toByteArray())
    }

    override fun ratchetSharedKey(keyIndex: Int?): ByteArray {
        return rtcKeyProvider.ratchetSharedKey(keyIndex ?: 0)
    }

    override fun exportSharedKey(keyIndex: Int?): ByteArray {
        return rtcKeyProvider.exportSharedKey(keyIndex ?: 0)
    }

    /**
     * Set a key for a participant
     * @param key
     * @param participantId
     * @param keyIndex
     */
    override fun setKey(key: String, participantId: String?, keyIndex: Int?) {
        if (enableSharedKey) {
            return
        }

        if (participantId == null) {
            LKLog.d { "Please provide valid participantId for non-SharedKey mode." }
            return
        }

        var keyInfo = KeyInfo(participantId, keyIndex ?: 0, key)

        if (!keys.containsKey(keyInfo.participantId)) {
            keys[keyInfo.participantId] = mutableMapOf()
        }
        keys[keyInfo.participantId]!![keyInfo.keyIndex] = keyInfo.key
        rtcKeyProvider.setKey(participantId, keyInfo.keyIndex, key.toByteArray())
    }

    override fun ratchetKey(participantId: String, keyIndex: Int?): ByteArray {
        return rtcKeyProvider.ratchetKey(participantId, keyIndex ?: 0)
    }

    override fun exportKey(participantId: String, keyIndex: Int?): ByteArray {
        return rtcKeyProvider.exportKey(participantId, keyIndex ?: 0)
    }

    override fun setSifTrailer(trailer: ByteArray) {
        rtcKeyProvider.setSifTrailer(trailer)
    }

    override val rtcKeyProvider: FrameCryptorKeyProvider

    init {
        this.rtcKeyProvider = FrameCryptorFactory.createFrameCryptorKeyProvider(
            enableSharedKey,
            ratchetSalt.toByteArray(),
            ratchetWindowSize,
            uncryptedMagicBytes.toByteArray(),
            failureTolerance,
            keyRingSize,
            discardFrameWhenCryptorNotReady,
        )
    }
}
