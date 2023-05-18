package org.webrtc

import io.livekit.android.mock.MockRtpReceiver
import io.livekit.android.mock.MockRtpSender
import org.mockito.Mockito
import org.webrtc.RtpTransceiver.RtpTransceiverDirection

object MockRtpTransceiver {
    fun create(
        track: MediaStreamTrack,
        init: RtpTransceiver.RtpTransceiverInit = RtpTransceiver.RtpTransceiverInit()
    ): RtpTransceiver {
        val mock = Mockito.mock(RtpTransceiver::class.java)

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
                val sender = MockRtpSender.create()
                Mockito.`when`(mock.sender)
                    .then { sender }
            }

            else -> {}
        }

        when (direction) {
            RtpTransceiverDirection.SEND_RECV, RtpTransceiverDirection.RECV_ONLY -> {
                val receiver = MockRtpReceiver.create()
                Mockito.`when`(mock.receiver)
                    .then { receiver }
            }

            else -> {}
        }

        return mock
    }
}