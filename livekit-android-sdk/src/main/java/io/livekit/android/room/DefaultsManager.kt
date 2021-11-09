package io.livekit.android.room

import io.livekit.android.room.participant.AudioTrackPublishDefaults
import io.livekit.android.room.participant.VideoTrackPublishDefaults
import io.livekit.android.room.track.LocalAudioTrackOptions
import io.livekit.android.room.track.LocalVideoTrackOptions
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultsManager
@Inject
constructor() {
    var audioTrackCaptureDefaults: LocalAudioTrackOptions = LocalAudioTrackOptions()
    var audioTrackPublishDefaults: AudioTrackPublishDefaults = AudioTrackPublishDefaults()
    var videoTrackCaptureDefaults: LocalVideoTrackOptions = LocalVideoTrackOptions()
    var videoTrackPublishDefaults: VideoTrackPublishDefaults = VideoTrackPublishDefaults()
}