/*
 * Copyright 2026 LiveKit, Inc.
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
import uniffi.livekit_datatrack.DecryptionException
import uniffi.livekit_datatrack.DecryptionProvider
import uniffi.livekit_datatrack.EncryptedPayload
import uniffi.livekit_datatrack.EncryptionException
import uniffi.livekit_datatrack.EncryptionProvider

/**
 * Bridges UniFFI data-track [EncryptionProvider] / [DecryptionProvider] to [E2EEManager].
 *
 * Adds no key handling of its own — encryption rides [E2EEManager]'s existing AES-GCM data path
 * (the same [DataPacketCryptorManager] used for data-channel payloads). The manager is resolved
 * per call so one assigned after connecting still applies.
 *
 * @suppress
 */
internal class DataTrackCryptor(
    private val e2eeManagerProvider: () -> E2EEManager?,
) : EncryptionProvider, DecryptionProvider {

    override fun encrypt(payload: ByteArray): EncryptedPayload {
        val manager = requireManager { message -> EncryptionException.Failed(message) }
        val packet = manager.encrypt(payload)
            ?: throw EncryptionException.Failed("Failed to encrypt data track payload")
        return EncryptedPayload(
            payload = packet.payload,
            iv = packet.iv,
            keyIndex = packet.keyIndex.toUByte(),
        )
    }

    override fun decrypt(payload: EncryptedPayload, senderIdentity: String): ByteArray {
        val manager = requireManager { message -> DecryptionException.Failed(message) }
        val packet = EncryptedPacket(
            payload = payload.payload,
            iv = payload.iv,
            keyIndex = payload.keyIndex.toInt(),
        )
        return manager.decrypt(Participant.Identity(senderIdentity), packet)
            ?: throw DecryptionException.Failed("Failed to decrypt data track payload")
    }

    private fun <T : Exception> requireManager(failed: (String) -> T): E2EEManager {
        return e2eeManagerProvider()
            ?: throw failed("Room has no E2EE manager")
    }
}
