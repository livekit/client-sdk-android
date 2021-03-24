package io.livekit.android.room.track

class RemoteVideoTrack(
    override var sid: String,
    var switchedOff: Boolean = false,
    name: String,
    mediaTrack: org.webrtc.VideoTrack
) : VideoTrack(name, mediaTrack), RemoteTrack
