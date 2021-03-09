package io.livekit.android.room.participant

import io.livekit.android.room.track.Track
import io.livekit.android.room.track.TrackPublication

class Participant(val sid: String, name: String? = null) {
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

    fun addTrack(publication: TrackPublication){
        tracks[publication.trackSid] = publication
        when(publication) {

        }
    }
}