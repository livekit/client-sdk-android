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

package io.livekit.android.room.track

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import java.util.*

/**
 * Represents a local audio track (generally using the microphone as input).
 *
 * This class should not be constructed directly, but rather through [LocalParticipant]
 */
class LocalAudioTrack(
    name: String,
    mediaTrack: org.webrtc.AudioTrack
) : AudioTrack(name, mediaTrack) {
    var enabled: Boolean
        get() = rtcTrack.enabled()
        set(value) {
            rtcTrack.setEnabled(value)
        }

    internal var transceiver: RtpTransceiver? = null
    private val sender: RtpSender?
        get() = transceiver?.sender

    companion object {
        internal fun createTrack(
            context: Context,
            factory: PeerConnectionFactory,
            options: LocalAudioTrackOptions = LocalAudioTrackOptions(),
            name: String = ""
        ): LocalAudioTrack {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                throw SecurityException("Record audio permissions are required to create an audio track.")
            }

            val audioConstraints = MediaConstraints()
            val items = listOf(
                MediaConstraints.KeyValuePair("googEchoCancellation", options.echoCancellation.toString()),
                MediaConstraints.KeyValuePair("googAutoGainControl", options.autoGainControl.toString()),
                MediaConstraints.KeyValuePair("googHighpassFilter", options.highPassFilter.toString()),
                MediaConstraints.KeyValuePair("googNoiseSuppression", options.noiseSuppression.toString()),
                MediaConstraints.KeyValuePair("googTypingNoiseDetection", options.typingNoiseDetection.toString()),
            )
            audioConstraints.optional.addAll(items)

            val audioSource = factory.createAudioSource(audioConstraints)
            val rtcAudioTrack =
                factory.createAudioTrack(UUID.randomUUID().toString(), audioSource)

            return LocalAudioTrack(name = name, mediaTrack = rtcAudioTrack)
        }
    }
}
