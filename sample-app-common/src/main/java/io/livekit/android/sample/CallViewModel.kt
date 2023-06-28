package io.livekit.android.sample

import android.app.Application
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.github.ajalt.timberkt.Timber
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.RoomOptions
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.e2ee.E2EEOptions
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalScreencastVideoTrack
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.sample.service.ForegroundService
import io.livekit.android.util.flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class CallViewModel(
    val url: String,
    val token: String,
    e2ee: Boolean?,
    e2eeKey: String?,
    application: Application
) : AndroidViewModel(application) {


    val audioHandler = AudioSwitchHandler(application)

    var e2ee: Boolean = false;

    var e2eeKey: String? = ""

    private fun getE2EEOptions(): E2EEOptions? {
        var e2eeOptions: E2EEOptions? = null
        if(e2ee && e2eeKey != null) {
            e2eeOptions = E2EEOptions()
        }
        e2eeOptions?.keyProvider?.setKey(e2eeKey!!, null, 0)
        return e2eeOptions
    }



    val room = LiveKit.create(
        appContext = application,
        options = RoomOptions(adaptiveStream = true, dynacast = true, e2eeOptions = getE2EEOptions()),
        overrides = LiveKitOverrides(
            audioHandler = audioHandler
        )
    )

    val participants = room::remoteParticipants.flow
        .map { remoteParticipants ->
            listOf<Participant>(room.localParticipant) +
                    remoteParticipants
                        .keys
                        .sortedBy { it }
                        .mapNotNull { remoteParticipants[it] }
        }

    private val mutableError = MutableStateFlow<Throwable?>(null)
    val error = mutableError.hide()

    private val mutablePrimarySpeaker = MutableStateFlow<Participant?>(null)
    val primarySpeaker: StateFlow<Participant?> = mutablePrimarySpeaker

    val activeSpeakers = room::activeSpeakers.flow

    private var localScreencastTrack: LocalScreencastVideoTrack? = null

    // Controls
    private val mutableMicEnabled = MutableLiveData(true)
    val micEnabled = mutableMicEnabled.hide()

    private val mutableCameraEnabled = MutableLiveData(true)
    val cameraEnabled = mutableCameraEnabled.hide()

    private val mutableScreencastEnabled = MutableLiveData(false)
    val screenshareEnabled = mutableScreencastEnabled.hide()

    // Emits a string whenever a data message is received.
    private val mutableDataReceived = MutableSharedFlow<String>()
    val dataReceived = mutableDataReceived

    // Whether other participants are allowed to subscribe to this participant's tracks.
    private val mutablePermissionAllowed = MutableStateFlow(true)
    val permissionAllowed = mutablePermissionAllowed.hide()

    init {
        this.e2ee = e2ee ?: false
        this.e2eeKey = e2eeKey
        viewModelScope.launch {
            // Collect any errors.
            launch {
                error.collect { Timber.e(it) }
            }

            // Handle any changes in speakers.
            launch {
                combine(participants, activeSpeakers) { participants, speakers -> participants to speakers }
                    .collect { (participantsList, speakers) ->
                        handlePrimarySpeaker(
                            participantsList,
                            speakers,
                            room
                        )
                    }
            }

            // Handle room events.
            launch {
                room.events.collect {
                    when (it) {
                        is RoomEvent.FailedToConnect -> mutableError.value = it.error
                        is RoomEvent.DataReceived -> {
                            val identity = it.participant?.identity ?: "server"
                            val message = it.data.toString(Charsets.UTF_8)
                            mutableDataReceived.emit("$identity: $message")
                        }

                        is RoomEvent.TrackSubscribed -> {
                            launch { collectTrackStats(it) }
                        }

                        else -> {
                            Timber.e { "Room event: $it" }
                        }
                    }
                }
            }

            connectToRoom()
        }


        // Start a foreground service to keep the call from being interrupted if the
        // app goes into the background.
        val foregroundServiceIntent = Intent(application, ForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            application.startForegroundService(foregroundServiceIntent)
        } else {
            application.startService(foregroundServiceIntent)
        }
    }

    private suspend fun collectTrackStats(event: RoomEvent.TrackSubscribed) {
        val pub = event.publication
        while (true) {
            delay(10000)
            if (pub.subscribed) {
                val statsReport = pub.track?.getRTCStats() ?: continue
                Timber.e { "stats for ${pub.sid}:" }

                for (entry in statsReport.statsMap) {
                    Timber.e { "${entry.key} = ${entry.value}" }
                }
            }
        }

    }

    private suspend fun connectToRoom() {
        try {
            room.connect(
                url = url,
                token = token,
                roomOptions = RoomOptions(e2eeOptions = getE2EEOptions())
            )

            // Create and publish audio/video tracks
            val localParticipant = room.localParticipant
            localParticipant.setMicrophoneEnabled(true)
            mutableMicEnabled.postValue(localParticipant.isMicrophoneEnabled())

            localParticipant.setCameraEnabled(true)
            mutableCameraEnabled.postValue(localParticipant.isCameraEnabled())

            // Update the speaker
            handlePrimarySpeaker(emptyList(), emptyList(), room)
        } catch (e: Throwable) {
            mutableError.value = e
        }
    }

    private fun handlePrimarySpeaker(participantsList: List<Participant>, speakers: List<Participant>, room: Room?) {

        var speaker = mutablePrimarySpeaker.value

        // If speaker is local participant (due to defaults),
        // attempt to find another remote speaker to replace with.
        if (speaker is LocalParticipant) {
            val remoteSpeaker = participantsList
                .filterIsInstance<RemoteParticipant>() // Try not to display local participant as speaker.
                .firstOrNull()

            if (remoteSpeaker != null) {
                speaker = remoteSpeaker
            }
        }

        // If previous primary speaker leaves
        if (!participantsList.contains(speaker)) {
            // Default to another person in room, or local participant.
            speaker = participantsList.filterIsInstance<RemoteParticipant>()
                .firstOrNull()
                ?: room?.localParticipant
        }

        if (speakers.isNotEmpty() && !speakers.contains(speaker)) {
            val remoteSpeaker = speakers
                .filterIsInstance<RemoteParticipant>() // Try not to display local participant as speaker.
                .firstOrNull()

            if (remoteSpeaker != null) {
                speaker = remoteSpeaker
            }
        }

        mutablePrimarySpeaker.value = speaker
    }

    /**
     * Start a screen capture with the result intent from
     * [MediaProjectionManager.createScreenCaptureIntent]
     */
    fun startScreenCapture(mediaProjectionPermissionResultData: Intent) {
        val localParticipant = room.localParticipant
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
                room.localParticipant.unpublishTrack(localScreencastVideoTrack)
                mutableScreencastEnabled.postValue(localScreencastTrack?.enabled ?: false)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        room.disconnect()
        room.release()

        // Clean up foreground service
        val application = getApplication<Application>()
        val foregroundServiceIntent = Intent(application, ForegroundService::class.java)
        application.stopService(foregroundServiceIntent)
    }

    fun setMicEnabled(enabled: Boolean) {
        viewModelScope.launch {
            room.localParticipant.setMicrophoneEnabled(enabled)
            mutableMicEnabled.postValue(enabled)
        }
    }

    fun setCameraEnabled(enabled: Boolean) {
        viewModelScope.launch {
            room.localParticipant.setCameraEnabled(enabled)
            mutableCameraEnabled.postValue(enabled)
        }
    }

    fun flipCamera() {
        val videoTrack = room.localParticipant.getTrackPublication(Track.Source.CAMERA)
            ?.track as? LocalVideoTrack
            ?: return

        val newPosition = when (videoTrack.options.position) {
            CameraPosition.FRONT -> CameraPosition.BACK
            CameraPosition.BACK -> CameraPosition.FRONT
            else -> null
        }

        videoTrack.switchCamera(position = newPosition)
    }

    fun dismissError() {
        mutableError.value = null
    }

    fun sendData(message: String) {
        viewModelScope.launch {
            room.localParticipant.publishData(message.toByteArray(Charsets.UTF_8))
        }
    }

    fun toggleSubscriptionPermissions() {
        mutablePermissionAllowed.value = !mutablePermissionAllowed.value
        room.localParticipant.setTrackSubscriptionPermissions(mutablePermissionAllowed.value)
    }

    // Debug functions
    fun simulateMigration() {
        room.sendSimulateScenario(Room.SimulateScenario.MIGRATION)
    }

    fun simulateNodeFailure() {
        room.sendSimulateScenario(Room.SimulateScenario.NODE_FAILURE)
    }

    fun reconnect() {
        Timber.e { "Reconnecting." }
        mutablePrimarySpeaker.value = null
        room.disconnect()
        viewModelScope.launch {
            connectToRoom()
        }
    }
}

private fun <T> LiveData<T>.hide(): LiveData<T> = this
private fun <T> MutableStateFlow<T>.hide(): StateFlow<T> = this
private fun <T> Flow<T>.hide(): Flow<T> = this