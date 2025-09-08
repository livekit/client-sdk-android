package io.livekit.android.test.mock.room.participant

import io.livekit.android.room.RTCEngine
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.RemoteAudioTrack
import io.livekit.android.room.track.RemoteVideoTrack
import io.livekit.android.test.mock.room.track.TestRemoteAudioTrackFactory
import io.livekit.android.test.mock.room.track.TestRemoteVideoTrackFactory
import kotlinx.coroutines.CoroutineDispatcher
import livekit.LivekitModels

class TestRemoteParticipantFactory(
    val rtcEngine: RTCEngine,
    val ioDispatcher: CoroutineDispatcher,
    val defaultDispatcher: CoroutineDispatcher,
    val audioTrackFactory: RemoteAudioTrack.Factory = TestRemoteAudioTrackFactory,
    val videoTrackFactory: RemoteVideoTrack.Factory = TestRemoteVideoTrackFactory(defaultDispatcher),
) : RemoteParticipant.Factory {
    override fun create(info: LivekitModels.ParticipantInfo): RemoteParticipant {
        return RemoteParticipant(
            info = info,
            signalClient = rtcEngine.client,
            ioDispatcher = ioDispatcher,
            defaultDispatcher = defaultDispatcher,
            audioTrackFactory = audioTrackFactory,
            videoTrackFactory = videoTrackFactory,
        )
    }
}
