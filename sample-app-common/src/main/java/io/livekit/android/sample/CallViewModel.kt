package io.livekit.android.sample

import android.app.Application
import android.content.Intent
import androidx.lifecycle.*
import com.github.ajalt.timberkt.Timber
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.RoomListener
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.*
import io.livekit.android.util.flow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import okio.Utf8
import java.nio.charset.Charset

@OptIn(ExperimentalCoroutinesApi::class)
class CallViewModel(
    val url: String,
    val token: String,
    application: Application
) : AndroidViewModel(application), RoomListener {
    private val mutableRoom = MutableStateFlow<Room?>(null)
    val room: MutableStateFlow<Room?> = mutableRoom
    val participants = mutableRoom.flatMapLatest { room ->
        if (room != null) {
            room::remoteParticipants.flow
                .map { remoteParticipants ->
                    listOf<Participant>(room.localParticipant) +
                            remoteParticipants
                                .keys
                                .sortedBy { it }
                                .mapNotNull { remoteParticipants[it] }
                }
        } else {
            emptyFlow()
        }
    }

    private val mutableError = MutableStateFlow<Throwable?>(null)
    val error = mutableError.hide()

    private val mutablePrimarySpeaker = MutableStateFlow<Participant?>(null)
    val primarySpeaker: StateFlow<Participant?> = mutablePrimarySpeaker

    val activeSpeakers = mutableRoom.flatMapLatest { room ->
        if (room != null) {
            room::activeSpeakers.flow
        } else {
            emptyFlow()
        }
    }

    private var localScreencastTrack: LocalScreencastVideoTrack? = null

    private val mutableMicEnabled = MutableLiveData(true)
    val micEnabled = mutableMicEnabled.hide()

    private val mutableCameraEnabled = MutableLiveData(true)
    val cameraEnabled = mutableCameraEnabled.hide()

    private val mutableFlipVideoButtonEnabled = MutableLiveData(true)
    val flipButtonVideoEnabled = mutableFlipVideoButtonEnabled.hide()

    private val mutableScreencastEnabled = MutableLiveData(false)
    val screenshareEnabled = mutableScreencastEnabled.hide()

    private val mutableDataReceived = MutableSharedFlow<String>()
    val dataReceived = mutableDataReceived

    init {
        viewModelScope.launch {
            try {
                val room = LiveKit.connect(
                    application,
                    url,
                    token,
                    roomOptions = RoomOptions(autoManageVideo = true),
                    listener = this@CallViewModel
                )

                // Create and publish audio/video tracks
                val localParticipant = room.localParticipant
                localParticipant.setMicrophoneEnabled(true)
                mutableMicEnabled.postValue(localParticipant.isMicrophoneEnabled())

                localParticipant.setCameraEnabled(true)
                mutableCameraEnabled.postValue(localParticipant.isCameraEnabled())
                mutableRoom.value = room

                mutablePrimarySpeaker.value = room.remoteParticipants.values.firstOrNull() ?: localParticipant

                viewModelScope.launch {
                    room.events.collect {
                        when (it) {
                            is RoomEvent.FailedToConnect -> mutableError.value = it.error
                            is RoomEvent.DataReceived -> {
                                val identity = it.participant.identity ?: ""
                                val message = it.data.toString(Charsets.UTF_8)
                                mutableDataReceived.emit("$identity: $message")
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                mutableError.value = e
            }
        }
    }

    fun startScreenCapture(mediaProjectionPermissionResultData: Intent) {
        val localParticipant = room.value?.localParticipant ?: return
        viewModelScope.launch {
            val screencastTrack =
                localParticipant.createScreencastTrack(mediaProjectionPermissionResultData = mediaProjectionPermissionResultData)
            localParticipant.publishVideoTrack(
                screencastTrack
            )

            // Must start the foreground prior to startCapture.
            screencastTrack.startForegroundService(null, null)
            screencastTrack.startCapture()

            this@CallViewModel.localScreencastTrack = screencastTrack
            mutableScreencastEnabled.postValue(screencastTrack.enabled)
        }
    }

    fun stopScreenCapture() {
        viewModelScope.launch {
            localScreencastTrack?.let { localScreencastVideoTrack ->
                localScreencastVideoTrack.stop()
                room.value?.localParticipant?.unpublishTrack(localScreencastVideoTrack)
                mutableScreencastEnabled.postValue(localScreencastTrack?.enabled ?: false)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        mutableRoom.value?.disconnect()
    }

    override fun onDisconnect(room: Room, error: Exception?) {
    }

    override fun onActiveSpeakersChanged(speakers: List<Participant>, room: Room) {
        // If old active speaker is still active, don't change.
        if (speakers.isEmpty() || speakers.contains(mutablePrimarySpeaker.value)) {
            return
        }
        val newSpeaker = speakers
            .filter { it is RemoteParticipant } // Try not to display local participant as speaker.
            .firstOrNull() ?: return
        mutablePrimarySpeaker.value = newSpeaker
    }

    fun setMicEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val localParticipant = room.value?.localParticipant ?: return@launch
            localParticipant.setMicrophoneEnabled(enabled)
            mutableMicEnabled.postValue(enabled)
        }
    }

    fun setCameraEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val localParticipant = room.value?.localParticipant ?: return@launch
            localParticipant.setCameraEnabled(enabled)
            mutableCameraEnabled.postValue(enabled)
        }
    }

    fun flipCamera() {
        room.value?.localParticipant?.let { participant ->
            val videoTrack = participant.getTrackPublication(Track.Source.CAMERA)
                ?.track as? LocalVideoTrack
                ?: return@let

            val newOptions = when (videoTrack.options.position) {
                CameraPosition.FRONT -> LocalVideoTrackOptions(position = CameraPosition.BACK)
                CameraPosition.BACK -> LocalVideoTrackOptions(position = CameraPosition.FRONT)
                else -> LocalVideoTrackOptions()
            }

            videoTrack.restartTrack(newOptions)
        }
    }

    fun dismissError() {
        mutableError.value = null
    }

    fun sendData(message: String) {
        viewModelScope.launch {
            room.value?.localParticipant?.publishData(message.toByteArray(Charsets.UTF_8))
        }
    }
}

private fun <T> LiveData<T>.hide(): LiveData<T> = this

private fun <T> MutableStateFlow<T>.hide(): StateFlow<T> = this
private fun <T> Flow<T>.hide(): Flow<T> = this