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

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.Room
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.RemoteAudioTrack
import io.livekit.android.room.track.RemoteVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.TrackPublication
import io.livekit.android.util.LKLog
import org.webrtc.FrameCryptor
import org.webrtc.FrameCryptor.FrameCryptionState
import org.webrtc.FrameCryptorAlgorithm
import org.webrtc.FrameCryptorFactory
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender

class E2EEManager
@AssistedInject
constructor(
    @Assisted keyProvider: KeyProvider,
    peerConnectionFactory: PeerConnectionFactory,
) {
    private var room: Room? = null
    private var keyProvider: KeyProvider
    private var frameCryptors = mutableMapOf<String, FrameCryptor>()
    private var algorithm: FrameCryptorAlgorithm = FrameCryptorAlgorithm.AES_GCM
    private lateinit var emitEvent: (roomEvent: RoomEvent) -> Unit?
    var enabled: Boolean = false

    init {
        this.keyProvider = keyProvider
    }

    suspend fun setup(room: Room, emitEvent: (roomEvent: RoomEvent) -> Unit) {
        if (this.room != room) {
            // E2EEManager already setup, clean up first
            cleanUp()
        }
        this.enabled = true
        this.room = room
        this.emitEvent = emitEvent
        this.room?.localParticipant?.tracks?.forEach() { item ->
            var participant = this.room!!.localParticipant
            var publication = item.value
            if (publication.track != null) {
                addPublishedTrack(publication.track!!, publication, participant, room)
            }
        }
        this.room?.remoteParticipants?.forEach() { item ->
            var participant = item.value
            participant.tracks.forEach() { item ->
                var publication = item.value
                if (publication.track != null) {
                    addSubscribedTrack(publication.track!!, publication, participant, room)
                }
            }
        }
    }

    public fun addSubscribedTrack(track: Track, publication: TrackPublication, participant: RemoteParticipant, room: Room) {
        var trackId = publication.sid
        var participantId = participant.sid
        var rtpReceiver: RtpReceiver? = when (publication.track!!) {
            is RemoteAudioTrack -> (publication.track!! as RemoteAudioTrack).receiver
            is RemoteVideoTrack -> (publication.track!! as RemoteVideoTrack).receiver
            else -> {
                throw IllegalArgumentException("unsupported track type")
            }
        }
        var frameCryptor = addRtpReceiver(rtpReceiver!!, participantId, trackId, publication.track!!.kind.name.lowercase())
        frameCryptor.setObserver { trackId, state ->
            LKLog.i { "Receiver::onFrameCryptionStateChanged: $trackId, state:  $state" }
            emitEvent(
                RoomEvent.TrackE2EEStateEvent(
                    room!!,
                    publication.track!!,
                    publication,
                    participant,
                    state = e2eeStateFromFrameCryptoState(state),
                ),
            )
        }
    }

    public fun addPublishedTrack(track: Track, publication: TrackPublication, participant: LocalParticipant, room: Room) {
        var trackId = publication.sid
        var participantId = participant.sid
        var rtpSender: RtpSender? = when (publication.track!!) {
            is LocalAudioTrack -> (publication.track!! as LocalAudioTrack)?.sender
            is LocalVideoTrack -> (publication.track!! as LocalVideoTrack)?.sender
            else -> {
                throw IllegalArgumentException("unsupported track type")
            }
        } ?: throw IllegalArgumentException("rtpSender is null")

        var frameCryptor = addRtpSender(rtpSender!!, participantId, trackId, publication.track!!.kind.name.lowercase())
        frameCryptor.setObserver { trackId, state ->
            LKLog.i { "Sender::onFrameCryptionStateChanged: $trackId, state:  $state" }
            emitEvent(
                RoomEvent.TrackE2EEStateEvent(
                    room!!,
                    publication.track!!,
                    publication,
                    participant,
                    state = e2eeStateFromFrameCryptoState(state),
                ),
            )
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
            else -> E2EEState.INTERNAL_ERROR
        }
    }

    private fun addRtpSender(sender: RtpSender, participantId: String, trackId: String, kind: String): FrameCryptor {
        var pid = "$kind-sender-$participantId-$trackId"
        var frameCryptor = FrameCryptorFactory.createFrameCryptorForRtpSender(
            sender,
            pid,
            algorithm,
            keyProvider.rtcKeyProvider,
        )

        frameCryptors[trackId] = frameCryptor
        frameCryptor.setEnabled(enabled)
        if (keyProvider.enableSharedKey) {
            keyProvider.rtcKeyProvider?.setKey(pid, 0, keyProvider?.sharedKey)
            frameCryptor.setKeyIndex(0)
        }

        return frameCryptor
    }

    private fun addRtpReceiver(receiver: RtpReceiver, participantId: String, trackId: String, kind: String): FrameCryptor {
        var pid = "$kind-receiver-$participantId-$trackId"
        var frameCryptor = FrameCryptorFactory.createFrameCryptorForRtpReceiver(
            receiver,
            pid,
            algorithm,
            keyProvider.rtcKeyProvider,
        )

        frameCryptors[trackId] = frameCryptor
        frameCryptor.setEnabled(enabled)

        if (keyProvider.enableSharedKey) {
            keyProvider.rtcKeyProvider?.setKey(pid, 0, keyProvider?.sharedKey)
            frameCryptor.setKeyIndex(0)
        }

        return frameCryptor
    }

    /**
     * Enable or disable E2EE
     * @param enabled
     */
    public fun enableE2EE(enabled: Boolean) {
        this.enabled = enabled
        for (item in frameCryptors.entries) {
            var frameCryptor = item.value
            var participantId = item.key
            frameCryptor.setEnabled(enabled)
            if (keyProvider.enableSharedKey) {
                keyProvider.rtcKeyProvider?.setKey(participantId, 0, keyProvider?.sharedKey)
                frameCryptor.setKeyIndex(0)
            }
        }
    }

    /**
     * Ratchet key for local participant
     */
    fun ratchetKey() {
        for (participantId in frameCryptors.keys) {
            var newKey = keyProvider.rtcKeyProvider?.ratchetKey(participantId, 0)
            LKLog.d { "ratchetKey: newKey: $newKey" }
        }
    }

    fun cleanUp() {
        for (frameCryptor in frameCryptors.values) {
            frameCryptor.dispose()
        }
        frameCryptors.clear()
    }

    @AssistedFactory
    interface Factory {
        fun create(
            @Assisted keyProvider: KeyProvider,
        ): E2EEManager
    }
}
