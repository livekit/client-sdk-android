package io.livekit.android.room

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import io.livekit.android.ConnectOptions
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.RemoteParticipant

class Room
@AssistedInject
constructor(
    @Assisted private val connectOptions: ConnectOptions,
    private val engine: RTCEngine,
) {

    enum class State {
        CONNECTING,
        CONNECTED,
        DISCONNECTED,
        RECONNECTING;
    }

    inline class Sid(val sid: String)

    var listener: Listener? = null

    var sid: Sid? = null
        private set
    var name: String? = null
        private set
    var state: State = State.DISCONNECTED
        private set
    var localParticipant: LocalParticipant? = TODO()


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