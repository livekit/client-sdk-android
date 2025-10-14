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

package io.livekit.android.test.mock.e2ee

import io.livekit.android.e2ee.DataPacketCryptorManager
import io.livekit.android.e2ee.EncryptedPacket
import io.livekit.android.room.participant.Participant

class ReversingDataPacketCryptorManager : DataPacketCryptorManager {
    override fun encrypt(
        participantId: Participant.Identity,
        keyIndex: Int,
        payload: ByteArray,
    ): EncryptedPacket? {
        return EncryptedPacket(
            payload = payload.reversedArray(),
            iv = "$participantId,$keyIndex".toByteArray(),
            keyIndex = keyIndex,
        )
    }

    override fun decrypt(participantId: Participant.Identity, packet: EncryptedPacket): ByteArray? {
        return packet.payload.reversedArray()
    }

    override fun dispose() {
    }
}
