package io.livekit.android.room.track

import org.webrtc.AudioTrack
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver

class RemoteAudioTrack(name: String, rtcTrack: AudioTrack, internal val receiver: RtpReceiver) : io.livekit.android.room.track.AudioTrack(name, rtcTrack)
{
    internal var receiver: RtpReceiver
    init {
        this.receiver = receiver
    }
}
