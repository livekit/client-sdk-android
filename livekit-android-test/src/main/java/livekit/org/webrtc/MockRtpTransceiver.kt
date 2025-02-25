/*
 * Copyright 2023-2025 LiveKit, Inc.
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

package livekit.org.webrtc

import io.livekit.android.test.mock.MockRtpReceiver
import io.livekit.android.test.mock.MockRtpSender
import livekit.org.webrtc.RtpTransceiver.RtpTransceiverDirection
import org.mockito.Mockito
import java.util.UUID

object MockRtpTransceiver {
    fun create(
        track: MediaStreamTrack,
        init: RtpTransceiver.RtpTransceiverInit = RtpTransceiver.RtpTransceiverInit(),
    ): RtpTransceiver {
        val mock = Mockito.mock(RtpTransceiver::class.java)
        val id = UUID.randomUUID().toString()
        Mockito.`when`(mock.mediaType).then {
            return@then when (track.kind()) {
                MediaStreamTrack.AUDIO_TRACK_KIND -> MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO
                MediaStreamTrack.VIDEO_TRACK_KIND -> MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO
                else -> throw IllegalStateException("illegal kind: ${track.kind()}")
            }
        }

        val direction = RtpTransceiverDirection.fromNativeIndex(init.directionNativeIndex)

        when (direction) {
            RtpTransceiverDirection.SEND_RECV, RtpTransceiverDirection.SEND_ONLY -> {
                val sender = MockRtpSender.create(id = id)
                Mockito.`when`(mock.sender)
                    .then { sender }
            }

            else -> {}
        }

        when (direction) {
            RtpTransceiverDirection.SEND_RECV, RtpTransceiverDirection.RECV_ONLY -> {
                val receiver = MockRtpReceiver.create(id = id)
                Mockito.`when`(mock.receiver)
                    .then { receiver }
            }

            else -> {}
        }

        return mock
    }
}
