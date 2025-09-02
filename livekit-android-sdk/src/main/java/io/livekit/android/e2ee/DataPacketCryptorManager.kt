package io.livekit.android.e2ee

import io.livekit.android.room.participant.Participant
import livekit.org.webrtc.DataPacketCryptor
import livekit.org.webrtc.DataPacketCryptorFactory
import livekit.org.webrtc.FrameCryptorAlgorithm

class DataPacketCryptorManager(
    private val keyProvider: KeyProvider,
) {
    private val dataPacketCryptor: DataPacketCryptor = DataPacketCryptorFactory.createDataPacketCryptor(FrameCryptorAlgorithm.AES_GCM, keyProvider.rtcKeyProvider)

    fun encrypt(participantId: Participant.Identity, keyIndex: Int, payload: ByteArray): DataPacketCryptor.EncryptedPacket? {
        return dataPacketCryptor.encrypt(
            participantId.value,
            keyIndex,
            payload,
        )
    }

    fun decrypt(participantId: Participant.Identity, packet: DataPacketCryptor.EncryptedPacket): ByteArray? {
        return dataPacketCryptor.decrypt(participantId.value, packet)
    }
}
