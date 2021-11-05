package io.livekit.android

import io.livekit.android.room.participant.AudioTrackPublishDefaults
import io.livekit.android.room.participant.VideoTrackPublishDefaults
import io.livekit.android.room.track.LocalAudioTrackOptions
import io.livekit.android.room.track.LocalVideoTrackOptions
import org.webrtc.PeerConnection


data class ConnectOptions(
    val autoSubscribe: Boolean = true,
    val iceServers: List<PeerConnection.IceServer>? = null,
    val rtcConfig: PeerConnection.RTCConfiguration? = null,
    /**
     * capture and publish audio track on connect, defaults to false
     */
    val audio: Boolean = false,
    /**
     * capture and publish video track on connect, defaults to false
     */
    val video: Boolean = false,

    val audioTrackCaptureDefaults: LocalAudioTrackOptions? = null,
    val videoTrackCaptureDefaults: LocalVideoTrackOptions? = null,
    val audioTrackPublishDefaults: AudioTrackPublishDefaults? = null,
    val videoTrackPublishDefaults: VideoTrackPublishDefaults? = null,
) {
    internal var reconnect: Boolean = false
}
