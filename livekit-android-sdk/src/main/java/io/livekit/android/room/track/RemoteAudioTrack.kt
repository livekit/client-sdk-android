package io.livekit.android.room.track

import org.webrtc.AudioTrack
import org.webrtc.RtpReceiver

class RemoteAudioTrack(name: String, rtcTrack: AudioTrack, internal val receiver: RtpReceiver) : io.livekit.android.room.track.AudioTrack(name, rtcTrack)
