/*
 * Copyright 2024 LiveKit, Inc.
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

package io.livekit.android.videoencodedecode

import android.app.Application
import android.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import com.github.ajalt.timberkt.Timber
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.RoomOptions
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.VideoTrackPublishDefaults
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.VideoCaptureParameter
import io.livekit.android.util.flow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import livekit.org.webrtc.EglBase

@OptIn(ExperimentalCoroutinesApi::class)
class CallViewModel(
    private val url: String,
    private val token: String,
    private val useDefaultVideoEncoder: Boolean = false,
    private val codecWhiteList: List<String>? = null,
    private val showVideo: Boolean,
    application: Application,
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
                            .sortedBy { it.value }
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
                            enableIntelVp8Encoder = true,
                            enableH264HighProfile = true,
                        )
                    } else {
                        WhitelistSimulcastVideoEncoderFactory(
                            EglBase.create().eglBaseContext,
                            enableIntelVp8Encoder = true,
                            enableH264HighProfile = true,
                        )
                    }
                    factory.apply { codecWhitelist = this@CallViewModel.codecWhiteList }
                } else {
                    null
                }
                val overrides = LiveKitOverrides(videoEncoderFactory = videoEncoderFactory)

                val room = LiveKit.create(
                    application,
                    options = RoomOptions(videoTrackPublishDefaults = VideoTrackPublishDefaults(simulcast = !useDefaultVideoEncoder)),
                    overrides = overrides,
                )
                room.connect(url, token)

                // Create and publish audio/video tracks
                val localParticipant = room.localParticipant

                if (showVideo) {
                    val capturer = DummyVideoCapturer(Color.RED)
                    val videoTrack = localParticipant.createVideoTrack(
                        capturer = capturer,
                        options = LocalVideoTrackOptions(captureParams = VideoCaptureParameter(128, 128, 30)),
                    )
                    videoTrack.startCapture()
                    localParticipant.publishVideoTrack(videoTrack)
                }
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
