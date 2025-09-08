package io.livekit.android.test.mock.room.track

import io.livekit.android.room.track.RemoteVideoTrack
import io.livekit.android.test.mock.MockRTCThreadToken
import kotlinx.coroutines.CoroutineDispatcher
import livekit.org.webrtc.RtpReceiver
import livekit.org.webrtc.VideoTrack

class TestRemoteVideoTrackFactory(val dispatcher: CoroutineDispatcher) : RemoteVideoTrack.Factory {
    override fun create(
        name: String,
        rtcTrack: VideoTrack,
        autoManageVideo: Boolean,
        receiver: RtpReceiver,
    ): RemoteVideoTrack {
        return RemoteVideoTrack(
            name = name,
            rtcTrack = rtcTrack,
            autoManageVideo = autoManageVideo,
            dispatcher = dispatcher,
            receiver = receiver,
            rtcThreadToken = MockRTCThreadToken(),
        )
    }
}
