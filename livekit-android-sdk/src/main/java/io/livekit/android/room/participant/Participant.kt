package io.livekit.android.room.participant

import io.livekit.android.room.track.*

open class Participant(var sid: String, name: String? = null) {
    inline class Sid(val sid: String)

    var metadata: String? = null
    var name: String? = name
        internal set
    var audioLevel: Float = 0f
        internal set

    var tracks = mutableMapOf<Track.Sid, TrackPublication>()
    var audioTracks = mutableMapOf<Track.Sid, TrackPublication>()
        private set
    var videoTracks = mutableMapOf<Track.Sid, TrackPublication>()
        private set
    var dataTracks = mutableMapOf<Track.Sid, TrackPublication>()
        private set

    fun addTrack(publication: TrackPublication) {
        tracks[publication.trackSid] = publication
        when (publication) {
            is RemoteAudioTrackPublication -> audioTracks[publication.trackSid] = publication
            is RemoteVideoTrackPublication -> videoTracks[publication.trackSid] = publication
            is RemoteDataTrackPublication -> dataTracks[publication.trackSid] = publication
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Participant

        if (sid != other.sid) return false

        return true
    }

    override fun hashCode(): Int {
        return sid.hashCode()
    }

}