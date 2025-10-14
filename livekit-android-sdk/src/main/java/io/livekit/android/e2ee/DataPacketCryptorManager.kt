/*
 * Copyright 2025 LiveKit, Inc.
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

import io.livekit.android.room.participant.Participant
import io.livekit.android.util.LKLog
import livekit.LivekitModels
import livekit.org.webrtc.DataPacketCryptor
import livekit.org.webrtc.DataPacketCryptorFactory
import livekit.org.webrtc.FrameCryptorAlgorithm

/**
 * @suppress
 */
interface DataPacketCryptorManager {
    fun encrypt(participantId: Participant.Identity, keyIndex: Int, payload: ByteArray): EncryptedPacket?
    fun decrypt(participantId: Participant.Identity, packet: EncryptedPacket): ByteArray?
    fun dispose()

    interface Factory {
        fun create(keyProvider: KeyProvider): DataPacketCryptorManager
    }
}

/**
 * @suppress
 */
class EncryptedPacket(
    val payload: ByteArray,
    val iv: ByteArray,
    val keyIndex: Int,
)

/**
 * @suppress
 */
fun LivekitModels.EncryptedPacket.toSdkType() =
    EncryptedPacket(
        payload = this.encryptedValue.toByteArray(),
        iv = this.iv.toByteArray(),
        keyIndex = this.keyIndex,
    )

internal class DataPacketCryptorManagerImpl(
    keyProvider: KeyProvider,
) : DataPacketCryptorManager {
    var isDisposed = false
    private val dataPacketCryptor: DataPacketCryptor = DataPacketCryptorFactory.createDataPacketCryptor(FrameCryptorAlgorithm.AES_GCM, keyProvider.rtcKeyProvider)

    @Synchronized
    override fun encrypt(participantId: Participant.Identity, keyIndex: Int, payload: ByteArray): EncryptedPacket? {
        if (isDisposed) {
            return null
        }
        val packet = dataPacketCryptor.encrypt(
            participantId.value,
            keyIndex,
            payload,
        )

        if (packet == null) {
            LKLog.i { "Error encrypting packet: null packet" }
            return null
        }

        val payload = packet.payload
        val iv = packet.iv
        val keyIndex = packet.keyIndex

        if (payload == null) {
            LKLog.w { "Error encrypting packet: null payload" }
            return null
        }
        if (iv == null) {
            LKLog.i { "Error encrypting packet: null iv returned" }
            return null
        }

        return EncryptedPacket(
            payload = payload,
            iv = iv,
            keyIndex = keyIndex,
        )
    }

    @Synchronized
    override fun decrypt(participantId: Participant.Identity, packet: EncryptedPacket): ByteArray? {
        if (isDisposed) {
            return null
        }
        return dataPacketCryptor.decrypt(
            participantId.value,
            DataPacketCryptor.EncryptedPacket(
                packet.payload,
                packet.iv,
                packet.keyIndex,
            ),
        )
    }

    @Synchronized
    override fun dispose() {
        if (isDisposed) {
            return
        }
        isDisposed = true
        dataPacketCryptor.dispose()
    }

    object Factory : DataPacketCryptorManager.Factory {
        override fun create(keyProvider: KeyProvider): DataPacketCryptorManager {
            return DataPacketCryptorManagerImpl(keyProvider)
        }
    }
}
