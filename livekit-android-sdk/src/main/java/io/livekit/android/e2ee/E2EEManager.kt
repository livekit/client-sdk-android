package io.livekit.android.e2ee

import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.*
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.RemoteAudioTrack
import io.livekit.android.room.track.RemoteVideoTrack
import org.webrtc.FrameCryptor
import org.webrtc.FrameCryptor.FrameCryptionState
import org.webrtc.FrameCryptorAlgorithm
import org.webrtc.FrameCryptorFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender

class E2EEManager
constructor(keyProvider: KeyProvider)  {
    var room: Room? = null
    private var keyProvider: KeyProvider
    var frameCryptors = mutableMapOf<String, FrameCryptor>()
    private var algorithm: FrameCryptorAlgorithm = FrameCryptorAlgorithm.AES_GCM
    var enabled: Boolean = false

    init {
        this.keyProvider = keyProvider
    }

    suspend fun setup(room: Room, emitEvent: (roomEvent: RoomEvent) -> Unit) {
        if (this.room != null) {
            // E2EEManager already setup, clean up first
            cleanUp();
        }
        this.room = room
        room.events.collect { event ->
            when (event) {
                is RoomEvent.TrackPublished -> {
                    var trackId = event.publication.sid;
                    var participantId = event.participant.sid;
                    var rtpSender: RtpSender? = when (event.publication.track!!) {
                        is LocalAudioTrack -> (event.publication.track!! as LocalAudioTrack)?.sender
                        is LocalVideoTrack -> (event.publication.track!! as LocalVideoTrack)?.sender
                        else -> {
                            throw IllegalArgumentException("unsupported track type")
                        }
                    } ?: throw IllegalArgumentException("rtpSender is null")

                    var frameCryptor = addRtpSender(rtpSender!!, participantId, trackId, event.publication.track!!.kind.name.lowercase());
                    frameCryptor.setObserver { trackId, state ->
                        println("Sender::onFrameCryptionStateChanged: $trackId, state:  $state");
                        emitEvent(
                            RoomEvent.TrackE2EEStateEvent(
                                room!!, event.publication.track!!, event.publication,
                                event.participant,
                                state = e2eeStateFromFrameCryptoState(state)
                            )
                        )
                    };
                }
                is RoomEvent.TrackSubscribed -> {
                    var trackId = event.publication.sid;
                    var participantId = event.participant.sid;
                    var rtpReceiver: RtpReceiver? = when (event.publication.track!!) {
                        is RemoteAudioTrack -> (event.publication.track!! as RemoteAudioTrack).receiver
                        is RemoteVideoTrack -> (event.publication.track!! as RemoteVideoTrack).receiver
                        else -> {
                            throw IllegalArgumentException("unsupported track type")
                        }
                    }

                    var frameCryptor = addRtpReceiver(rtpReceiver!!, participantId, trackId, event.publication.track!!.kind.name.lowercase());
                    frameCryptor.setObserver { trackId, state ->
                        println("Receiver::onFrameCryptionStateChanged: $trackId, state:  $state");
                        emitEvent(
                            RoomEvent.TrackE2EEStateEvent(
                                room!!, event.publication.track!!, event.publication,
                                event.participant,
                                state = e2eeStateFromFrameCryptoState(state)
                            )
                        )
                    };
                }
                else -> {}
            }
        }
    }

    private fun e2eeStateFromFrameCryptoState(state: FrameCryptionState?): E2EEState {
        return when (state) {
            FrameCryptionState.NEW -> E2EEState.NEW
            FrameCryptionState.OK -> E2EEState.OK
            FrameCryptionState.KEYRATCHETED -> E2EEState.KEY_RATCHETED
            FrameCryptionState.MISSINGKEY -> E2EEState.MISSING_KEY
            FrameCryptionState.ENCRYPTIONFAILED -> E2EEState.ENCRYPTION_FAILED
            FrameCryptionState.DECRYPTIONFAILED -> E2EEState.DECRYPTION_FAILED
            FrameCryptionState.INTERNALERROR -> E2EEState.INTERNAL_ERROR
            else -> { E2EEState.INTERNAL_ERROR}
        }
    }

    private fun addRtpSender(sender: RtpSender, participantId: String, trackId: String , kind: String): FrameCryptor {
        var pid = "$kind-sender-$participantId-$trackId";
        var frameCryptor = FrameCryptorFactory.createFrameCryptorForRtpSender(
            sender, pid, algorithm, keyProvider.rtcKeyProvider);

        frameCryptors[trackId] = frameCryptor;
        frameCryptor.setEnabled(enabled);
        if(keyProvider.enableSharedKey) {
            keyProvider.rtcKeyProvider?.setKey(pid, 0, keyProvider?.sharedKey);
            frameCryptor.setKeyIndex(0);
        }

        return frameCryptor;
    }

    private fun addRtpReceiver(receiver: RtpReceiver, participantId: String, trackId: String, kind: String): FrameCryptor {
        var pid = "$kind-receiver-$participantId-$trackId";
        var frameCryptor = FrameCryptorFactory.createFrameCryptorForRtpReceiver(
            receiver, pid, algorithm, keyProvider.rtcKeyProvider);

        frameCryptors[trackId] = frameCryptor;
        frameCryptor.setEnabled(enabled);

        if(keyProvider.enableSharedKey) {
            keyProvider.rtcKeyProvider?.setKey(pid, 0, keyProvider?.sharedKey);
            frameCryptor.setKeyIndex(0);
        }

        return frameCryptor;
    }

    fun enableE2EE(enabled: Boolean) {
        this.enabled = enabled;
        for (item in frameCryptors.entries) {
            var frameCryptor = item.value;
            var participantId = item.key;
            frameCryptor.setEnabled(enabled);
            if(keyProvider.enableSharedKey) {
                keyProvider.rtcKeyProvider?.setKey(participantId, 0, keyProvider?.sharedKey);
                frameCryptor.setKeyIndex(0);
            }
        }
    }

    fun ratchetKey() {
        for (participantId in frameCryptors.keys) {
            var newKey = keyProvider.rtcKeyProvider?.ratchetKey(participantId, 0);
            print("newKey: $newKey")
        }
    }

    fun cleanUp()  {
        for (frameCryptor in frameCryptors.values) {
            frameCryptor.dispose();
        }
        frameCryptors.clear();
    }
}
