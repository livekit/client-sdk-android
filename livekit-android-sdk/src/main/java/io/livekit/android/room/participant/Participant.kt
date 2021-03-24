package io.livekit.android.room.participant

import io.livekit.android.room.track.*
import livekit.LivekitModels

open class Participant(var sid: String, identity: String? = null) {
    var participantInfo: LivekitModels.ParticipantInfo? = null
        private set
    var identity: String? = identity
        internal set
    var audioLevel: Float = 0f
        internal set
    var metadata: String? = null
    var participantListener: Listener? = null
    val hasInfo
        get() = participantInfo != null

    var tracks = mutableMapOf<String, TrackPublication>()
    var audioTracks = mutableMapOf<String, TrackPublication>()
        private set
    var videoTracks = mutableMapOf<String, TrackPublication>()
        private set
    var dataTracks = mutableMapOf<String, TrackPublication>()
        private set

    /**
     * @suppress
     */
    fun addTrackPublication(publication: TrackPublication) {
        tracks[publication.sid] = publication
        when (publication.kind) {
            LivekitModels.TrackType.AUDIO -> audioTracks[publication.sid] = publication
            LivekitModels.TrackType.VIDEO -> videoTracks[publication.sid] = publication
            LivekitModels.TrackType.DATA -> dataTracks[publication.sid] = publication
        }
    }

    /**
     * @suppress
     */
    open fun updateFromInfo(info: LivekitModels.ParticipantInfo) {
        sid = info.sid
        identity = info.identity
        participantInfo = info

        val prevMetadata = metadata
        metadata = info.metadata

        if (prevMetadata != metadata) {
            participantListener?.onMetadataChanged(this, prevMetadata)
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

    interface Listener {
        fun onMetadataChanged(participant: Participant, prevMetadata: String?) {}
    }
}