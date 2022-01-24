package io.livekit.android.mock

import org.mockito.Mockito
import org.webrtc.MediaStreamTrack
import org.webrtc.RtpTransceiver

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

        return mock
    }
}