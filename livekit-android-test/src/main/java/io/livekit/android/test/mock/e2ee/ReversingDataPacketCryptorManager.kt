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
