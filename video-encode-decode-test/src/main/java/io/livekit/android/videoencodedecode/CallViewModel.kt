package io.livekit.android.videoencodedecode

import android.app.Application
import android.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import com.github.ajalt.timberkt.Timber
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.dagger.LiveKitOverrides
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.util.flow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.webrtc.EglBase

@OptIn(ExperimentalCoroutinesApi::class)
class CallViewModel(
    val url: String,
    val token: String,
    val useDefaultVideoEncoder: Boolean = false,
    val codecWhiteList: List<String>? = null,
    application: Application
) : AndroidViewModel(application) {
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
            flowOf(emptyList())
        }
    }

    private val mutableError = MutableStateFlow<Throwable?>(null)
    val error = mutableError.hide()

    init {
        viewModelScope.launch {

            launch {
                error.collect { Timber.e(it) }
            }

            try {
                val videoEncoderFactory = if (useDefaultVideoEncoder || codecWhiteList != null) {
                    val factory = if (useDefaultVideoEncoder) {
                        WhitelistDefaultVideoEncoderFactory(
                            EglBase.create().eglBaseContext,
                            true,
                            true
                        )
                    } else {
                        WhitelistSimulcastVideoEncoderFactory(
                            EglBase.create().eglBaseContext,
                            true,
                            true,
                        )
                    }
                    factory.apply { codecWhitelist = this@CallViewModel.codecWhiteList }
                } else {
                    null
                }
                val overrides = LiveKitOverrides(videoEncoderFactory = videoEncoderFactory)

                val room = LiveKit.connect(
                    application,
                    url,
                    token,
                    roomOptions = RoomOptions(adaptiveStream = true, dynacast = true),
                    overrides = overrides
                )

                // Create and publish audio/video tracks
                val localParticipant = room.localParticipant

                val capturer = DummyVideoCapturer(Color.RED)
                val videoTrack = localParticipant.createVideoTrack(capturer = capturer)
                videoTrack.startCapture()
                localParticipant.publishVideoTrack(videoTrack)
                mutableRoom.value = room

            } catch (e: Throwable) {
                mutableError.value = e
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        mutableRoom.value?.disconnect()
    }
}

private fun <T> LiveData<T>.hide(): LiveData<T> = this

private fun <T> MutableStateFlow<T>.hide(): StateFlow<T> = this
private fun <T> Flow<T>.hide(): Flow<T> = this