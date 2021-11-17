package io.livekit.android.sample

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.snakydesign.livedataextensions.distinctUntilChanged
import io.livekit.android.ConnectOptions
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.sample.util.hide
import kotlinx.coroutines.launch

class CallViewModel(
    val url: String,
    val token: String,
    application: Application
) : AndroidViewModel(application) {
    private val mutableRoom = MutableLiveData<Room>()
    val room = mutableRoom.hide()
    private val mutableParticipants = MutableLiveData<List<Participant>>()
    val participants = mutableParticipants.hide()
    private val mutableActiveSpeaker = MutableLiveData<Participant>()
    val activeSpeaker = mutableActiveSpeaker.hide().distinctUntilChanged()

    private val mutableVideoEnabled = MutableLiveData<Boolean>()
    val videoEnabled = mutableVideoEnabled.hide().distinctUntilChanged()
    private val mutableMicEnabled = MutableLiveData<Boolean>()
    val micEnabled = mutableMicEnabled.hide().distinctUntilChanged()
    private val mutableScreenshareEnabled = MutableLiveData<Boolean>()
    val screenshareEnabled = mutableScreenshareEnabled.hide().distinctUntilChanged()

    init {
        viewModelScope.launch {
            val room = LiveKit.connect(
                application,
                url,
                token,
                ConnectOptions(),
                null
            )

            launch {
                room.events.collect {
                    handleRoomEvent(it)
                }
            }

            val localParticipant = room.localParticipant
            val audioTrack = localParticipant.createAudioTrack()
            localParticipant.publishAudioTrack(audioTrack)
            val videoTrack = localParticipant.createVideoTrack()
            localParticipant.publishVideoTrack(videoTrack)
            videoTrack.startCapture()

            updateParticipants(room)
            mutableActiveSpeaker.value = localParticipant
            mutableRoom.value = room

            mutableVideoEnabled.value =
                !(localParticipant.getTrackPublication(Track.Source.CAMERA)?.muted ?: false)
            mutableMicEnabled.value =
                !(localParticipant.getTrackPublication(Track.Source.MICROPHONE)?.muted ?: false)
            mutableScreenshareEnabled.value = false
        }
    }

    private fun handleRoomEvent(event: RoomEvent) {
        when (event) {
            is RoomEvent.ParticipantConnected -> updateParticipants(event.room)
            is RoomEvent.ParticipantDisconnected -> updateParticipants(event.room)
            is RoomEvent.ActiveSpeakersChanged -> handleActiveSpeakersChanged(event.speakers)
        }
    }

    private fun updateParticipants(room: Room) {
        mutableParticipants.postValue(
            listOf(room.localParticipant) +
                    room.remoteParticipants
                        .keys
                        .sortedBy { it }
                        .mapNotNull { room.remoteParticipants[it] }
        )
    }

    fun handleActiveSpeakersChanged(speakers: List<Participant>) {
        // If old active speaker is still active, don't change.
        if (speakers.isEmpty() || speakers.contains(mutableActiveSpeaker.value)) {
            return
        }
        val newSpeaker = speakers.firstOrNull() ?: return
        mutableActiveSpeaker.postValue(newSpeaker)
    }

    override fun onCleared() {
        super.onCleared()
        mutableRoom.value?.disconnect()
    }

    fun setCameraEnabled(enabled: Boolean) {
        val localParticipant = room.value?.localParticipant ?: return

        viewModelScope.launch {
            localParticipant.setCameraEnabled(enabled)
            mutableVideoEnabled.postValue(enabled)
        }
    }

    fun setMicEnabled(enabled: Boolean) {
        val localParticipant = room.value?.localParticipant ?: return

        viewModelScope.launch {
            localParticipant.setMicrophoneEnabled(enabled)
            mutableMicEnabled.postValue(enabled)
        }
    }

    fun setScreenshare(
        enabled: Boolean,
        mediaProjectionPermissionResultData: Intent? = null
    ) {
        val localParticipant = room.value?.localParticipant ?: return

        viewModelScope.launch {
            localParticipant.setScreenShareEnabled(enabled, mediaProjectionPermissionResultData)
            mutableScreenshareEnabled.postValue(enabled)
        }
    }

    fun flipCamera() {
        val localParticipant = room.value?.localParticipant ?: return
        val localVideoTrack = localParticipant
            .getTrackPublication(Track.Source.CAMERA)
            ?.track as? LocalVideoTrack
            ?: return

        val currentOptions = localVideoTrack.options
        val newPosition = when (currentOptions.position) {
            CameraPosition.FRONT -> CameraPosition.BACK
            CameraPosition.BACK -> CameraPosition.FRONT
            null -> null
        }

        if (newPosition != null) {
            localVideoTrack.restartTrack(options = currentOptions.copy(position = newPosition))
        }
    }
}
