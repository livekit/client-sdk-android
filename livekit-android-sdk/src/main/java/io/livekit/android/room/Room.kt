package io.livekit.android.room

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import io.livekit.android.ConnectOptions
import io.livekit.android.room.participant.RemoteParticipant

class Room
@AssistedInject
constructor(
    @Assisted private val connectOptions: ConnectOptions,
    private val engine: RTCEngine,
) {

    var listener: Listener? = null
    suspend fun connect(url: String, token: String, isSecure: Boolean) {
        engine.join(url, token, isSecure)
    }

    @AssistedFactory
    interface Factory {
        fun create(connectOptions: ConnectOptions): Room
    }


    interface Listener {
        fun onConnect(room: Room)
        fun onDisconnect(room: Room, error: Exception)
        fun onParticipantDidConnect(room: Room, participant: RemoteParticipant)
    }
//    func didConnect(room: Room)
//    func didDisconnect(room: Room, error: Error?)
//    func participantDidConnect(room: Room, participant: RemoteParticipant)
//    func participantDidDisconnect(room: Room, participant: RemoteParticipant)
//    func didFailToConnect(room: Room, error: Error)
//    func isReconnecting(room: Room, error: Error)
//    func didReconnect(room: Room)
//    func didStartRecording(room: Room)
//    func didStopRecording(room: Room)
//    func activeSpeakersDidChange(speakers: [Participant], room: Room)
}