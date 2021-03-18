package io.livekit.android.sample

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.livekit.android.ConnectOptions
import io.livekit.android.LiveKit
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import kotlinx.coroutines.launch

class CallViewModel(
    val url: String,
    val token: String,
    application: Application
) : AndroidViewModel(application) {


    private val mutableRoom = MutableLiveData<Room>()
    val room: LiveData<Room> = mutableRoom
    private val mutableRemoteParticipants = MutableLiveData<List<RemoteParticipant>>()
    val remoteParticipants: LiveData<List<RemoteParticipant>> = mutableRemoteParticipants

    init {

        viewModelScope.launch {

            mutableRoom.value = LiveKit.connect(
                application,
                url,
                token,
                ConnectOptions(false),
                object : Room.Listener {
                    override fun onConnect(room: Room) {
                    }

                    override fun onDisconnect(room: Room, error: Exception?) {
                    }

                    override fun onParticipantConnected(
                        room: Room,
                        participant: RemoteParticipant
                    ) {
                        updateParticipants(room)
                    }

                    override fun onParticipantDisconnected(
                        room: Room,
                        participant: RemoteParticipant
                    ) {
                        updateParticipants(room)
                    }

                    override fun onFailedToConnect(room: Room, error: Exception) {
                    }

                    override fun onReconnecting(room: Room, error: Exception) {
                    }

                    override fun onReconnect(room: Room) {
                    }

                    override fun onStartRecording(room: Room) {
                    }

                    override fun onStopRecording(room: Room) {
                    }

                    override fun onActiveSpeakersChanged(speakers: List<Participant>, room: Room) {
                    }

                }
            )
        }
    }

    fun updateParticipants(room: Room) {
        mutableRemoteParticipants.value = room.remoteParticipants
            .keys
            .sortedBy { it.sid }
            .mapNotNull { room.remoteParticipants[it] }
    }

}
