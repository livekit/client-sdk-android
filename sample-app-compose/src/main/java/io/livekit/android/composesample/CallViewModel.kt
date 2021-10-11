package io.livekit.android.composesample

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.github.ajalt.timberkt.Timber
import io.livekit.android.ConnectOptions
import io.livekit.android.LiveKit
import io.livekit.android.room.Room
import io.livekit.android.room.RoomListener
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.room.track.LocalVideoTrack
import kotlinx.coroutines.launch

class CallViewModel(
    val url: String,
    val token: String,
    application: Application
) : AndroidViewModel(application), RoomListener {
    private val mutableRoom = MutableLiveData<Room>()
    val room: LiveData<Room> = mutableRoom
    private val mutableRemoteParticipants = MutableLiveData<List<RemoteParticipant>>()
    val remoteParticipants: LiveData<List<RemoteParticipant>> = mutableRemoteParticipants

    private var localAudioTrack: LocalAudioTrack? = null
    private var localVideoTrack: LocalVideoTrack? = null

    private val mutableMicEnabled = MutableLiveData(true)
    val micEnabled = mutableMicEnabled.hide()

    private val mutableVideoEnabled = MutableLiveData(true)
    val videoEnabled = mutableVideoEnabled.hide()

    private val mutableFlipVideoButtonEnabled = MutableLiveData(true)
    val flipButtonVideoEnabled = mutableFlipVideoButtonEnabled.hide()

    init {
        viewModelScope.launch {
            val room = LiveKit.connect(
                application,
                url,
                token,
                ConnectOptions(),
                this@CallViewModel
            )

            val localParticipant = room.localParticipant
            val audioTrack = localParticipant.createAudioTrack()
            localParticipant.publishAudioTrack(audioTrack)
            this@CallViewModel.localAudioTrack = audioTrack
            mutableMicEnabled.postValue(audioTrack.enabled)

            val videoTrack = localParticipant.createVideoTrack()
            localParticipant.publishVideoTrack(videoTrack)
            videoTrack.startCapture()
            this@CallViewModel.localVideoTrack = videoTrack
            mutableVideoEnabled.postValue(videoTrack.enabled)

            updateParticipants(room)
            mutableRoom.value = room
        }
    }

    private fun updateParticipants(room: Room) {
        mutableRemoteParticipants.postValue(
            room.remoteParticipants
                .keys
                .sortedBy { it }
                .mapNotNull { room.remoteParticipants[it] }
        )
    }

    override fun onCleared() {
        super.onCleared()
        mutableRoom.value?.disconnect()
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

    override fun onActiveSpeakersChanged(speakers: List<Participant>, room: Room) {
        Timber.i { "active speakers changed ${speakers.count()}" }
    }

    override fun onMetadataChanged(participant: Participant, prevMetadata: String?, room: Room) {
        Timber.i { "Participant metadata changed: ${participant.identity}" }
    }

    fun setMicEnabled(enabled: Boolean) {
        localAudioTrack?.enabled = enabled
        mutableMicEnabled.postValue(enabled)
    }

    fun setVideoEnabled(enabled: Boolean) {
        localVideoTrack?.enabled = enabled
        mutableVideoEnabled.postValue(enabled)
    }

    fun flipVideo() {
        // TODO
    }
}

private fun <T> LiveData<T>.hide(): LiveData<T> = this
