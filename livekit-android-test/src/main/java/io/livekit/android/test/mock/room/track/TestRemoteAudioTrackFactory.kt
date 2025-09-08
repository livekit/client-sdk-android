package io.livekit.android.test.mock.room.track

import io.livekit.android.room.track.RemoteAudioTrack
import io.livekit.android.test.mock.MockRTCThreadToken
import livekit.org.webrtc.AudioTrack
import livekit.org.webrtc.RtpReceiver

object TestRemoteAudioTrackFactory : RemoteAudioTrack.Factory {
    override fun create(
        name: String,
        rtcTrack: AudioTrack,
        receiver: RtpReceiver,
    ): RemoteAudioTrack {
        return RemoteAudioTrack(
            name = name,
            rtcTrack = rtcTrack,
            receiver = receiver,
            rtcThreadToken = MockRTCThreadToken(),
        )
    }

}
