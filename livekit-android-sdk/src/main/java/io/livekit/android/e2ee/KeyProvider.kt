/*
 * Copyright 2023 LiveKit, Inc.
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
import org.webrtc.FrameCryptorFactory
import org.webrtc.FrameCryptorKeyProvider

class KeyInfo
constructor(var participantId: String, var keyIndex: Int, var key: String ) {
    override fun toString(): String {
        return "KeyInfo(participantId='$participantId', keyIndex=$keyIndex)"
    }
}

 public interface KeyProvider {
    fun setKey(key: String, participantId: String?, keyIndex: Int?  = 0)
    fun ratchetKey(participantId: String, index: Int): ByteArray

    val rtcKeyProvider: FrameCryptorKeyProvider

    var sharedKey: ByteArray?

    var enableSharedKey: Boolean
}

class BaseKeyProvider
constructor(
    private var ratchetSalt: String,
    private var uncryptedMagicBytes: String,
    private var ratchetWindowSize: Int,
    override var enableSharedKey: Boolean = true,
    private var failureTolerance:Int,
) :
    KeyProvider {
    override var sharedKey: ByteArray? = null
    private var keys: MutableMap<String, MutableMap<Int, String>> = mutableMapOf()

    /**
     * Set a key for a participant
     * @param key
     * @param participantId
     * @param keyIndex
     */
    override fun setKey(key: String, participantId: String?, keyIndex: Int?) {
        if (enableSharedKey) {
            sharedKey = key.toByteArray()
            return
        }

        if(participantId == null) {
            LKLog.d{ "Please provide valid participantId for non-SharedKey mode." }
            return
        }

        var keyInfo = KeyInfo(participantId, keyIndex ?: 0, key)

        if (!keys.containsKey(keyInfo.participantId)) {
            keys[keyInfo.participantId] = mutableMapOf()
        }
        keys[keyInfo.participantId]!![keyInfo.keyIndex] = keyInfo.key
        rtcKeyProvider.setKey(participantId, keyInfo.keyIndex, key.toByteArray())
    }

    override fun ratchetKey(participantId: String, index: Int): ByteArray {
        return rtcKeyProvider.ratchetKey(participantId, index)
    }

    override val rtcKeyProvider: FrameCryptorKeyProvider

    init {
        this.rtcKeyProvider = FrameCryptorFactory.createFrameCryptorKeyProvider(
            enableSharedKey,
            ratchetSalt.toByteArray(),
            ratchetWindowSize,
            uncryptedMagicBytes.toByteArray(),
            failureTolerance
        )
    }
}
