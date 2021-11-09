package io.livekit.android.room.participant

import io.livekit.android.room.track.LocalTrackPublication
import io.livekit.android.room.track.RemoteTrackPublication
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.TrackPublication
import livekit.LivekitModels

open class Participant(var sid: String, identity: String? = null) {
    var participantInfo: LivekitModels.ParticipantInfo? = null
        private set
    var identity: String? = identity
        internal set
    var audioLevel: Float = 0f
        internal set
    var isSpeaking: Boolean = false
        internal set(v) {
            val changed = v == field
            field = v
            if (changed) {
                listener?.onSpeakingChanged(this)
                internalListener?.onSpeakingChanged(this)
            }
        }
    var metadata: String? = null
        internal set(v) {
            val prevMetadata = field
            field = v
            if (prevMetadata != v) {
                listener?.onMetadataChanged(this, prevMetadata)
                internalListener?.onMetadataChanged(this, prevMetadata)
            }
        }
    var connectionQuality: ConnectionQuality = ConnectionQuality.UNKNOWN
        internal set

    /**
     * Listener for when participant properties change
     */
    var listener: ParticipantListener? = null

    /**
     * @suppress
     */
    internal var internalListener: ParticipantListener? = null

    val hasInfo
        get() = participantInfo != null

    var tracks = mutableMapOf<String, TrackPublication>()
    var audioTracks = mutableMapOf<String, TrackPublication>()
        private set
    var videoTracks = mutableMapOf<String, TrackPublication>()
        private set

    /**
     * @suppress
     */
    fun addTrackPublication(publication: TrackPublication) {
        val track = publication.track
        track?.sid = publication.sid
        tracks[publication.sid] = publication
        when (publication.kind) {
            Track.Kind.AUDIO -> audioTracks[publication.sid] = publication
            Track.Kind.VIDEO -> videoTracks[publication.sid] = publication
            else -> {
            }
        }
    }

    /**
     * Retrieves the first track that matches the source, or null
     */
    open fun getTrackPublication(source: Track.Source): TrackPublication? {
        if (source == Track.Source.UNKNOWN) {
            return null
        }

        for ((_, pub) in tracks) {
            if (pub.source == source) {
                return pub
            }

            // Alternative heuristics for finding track if source is unknown
            if (pub.source == Track.Source.UNKNOWN) {
                if (source == Track.Source.MICROPHONE && pub.kind == Track.Kind.AUDIO) {
                    return pub
                }
                if (source == Track.Source.CAMERA && pub.kind == Track.Kind.VIDEO && pub.name != "screen") {
                    return pub
                }
                if (source == Track.Source.SCREEN_SHARE && pub.kind == Track.Kind.VIDEO && pub.name == "screen") {
                    return pub
                }
            }
        }
        return null
    }

    /**
     * Retrieves the first track that matches [name], or null
     */
    open fun getTrackPublicationByName(name: String): TrackPublication? {
        for ((_, pub) in tracks) {
            if (pub.name == name) {
                return pub
            }
        }
        return null
    }

    /**
     * @suppress
     */
    internal open fun updateFromInfo(info: LivekitModels.ParticipantInfo) {
        sid = info.sid
        identity = info.identity
        participantInfo = info
        metadata = info.metadata
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


interface ParticipantListener {
    // all participants
    /**
     * When a participant's metadata is updated, fired for all participants
     */
    fun onMetadataChanged(participant: Participant, prevMetadata: String?) {}

    /**
     * Fired when the current participant's isSpeaking property changes. (including LocalParticipant)
     */
    fun onSpeakingChanged(participant: Participant) {}

    /**
     * The participant was muted.
     *
     * For the local participant, the callback will be called if setMute was called on the
     * [LocalTrackPublication], or if the server has requested the participant to be muted
     */
    fun onTrackMuted(publication: TrackPublication, participant: Participant) {}

    /**
     * The participant was unmuted.
     *
     * For the local participant, the callback will be called if setMute was called on the
     * [LocalTrackPublication], or if the server has requested the participant to be muted
     */
    fun onTrackUnmuted(publication: TrackPublication, participant: Participant) {}


    // local participants
    /**
     * When a new track is published by the local participant.
     */
    fun onTrackPublished(publication: LocalTrackPublication, participant: LocalParticipant) {}

    /**
     * A [LocalParticipant] has unpublished a track
     */
    fun onTrackUnpublished(publication: LocalTrackPublication, participant: LocalParticipant) {}

    // remote participants
    /**
     * When a new track is published to room after the local participant has joined.
     *
     * It will not fire for tracks that are already published
     */
    fun onTrackPublished(publication: RemoteTrackPublication, participant: RemoteParticipant) {}

    /**
     * A [RemoteParticipant] has unpublished a track
     */
    fun onTrackUnpublished(publication: RemoteTrackPublication, participant: RemoteParticipant) {}

    /**
     * Subscribed to a new track
     */
    fun onTrackSubscribed(track: Track, publication: RemoteTrackPublication, participant: RemoteParticipant) {}

    /**
     * Error had occurred while subscribing to a track
     */
    fun onTrackSubscriptionFailed(
        sid: String,
        exception: Exception,
        participant: RemoteParticipant
    ) {
    }

    /**
     * A subscribed track is no longer available.
     * Clients should listen to this event and handle cleanup
     */
    fun onTrackUnsubscribed(
        track: Track,
        publication: RemoteTrackPublication,
        participant: RemoteParticipant
    ) {
    }

    /**
     * Received data published by another participant
     */
    fun onDataReceived(data: ByteArray, participant: RemoteParticipant) {}
}

enum class ConnectionQuality {
    EXCELLENT,
    GOOD,
    POOR,
    UNKNOWN;

    companion object {
        fun fromProto(proto: LivekitModels.ConnectionQuality): ConnectionQuality {
            return when (proto) {
                LivekitModels.ConnectionQuality.EXCELLENT -> EXCELLENT
                LivekitModels.ConnectionQuality.GOOD -> GOOD
                LivekitModels.ConnectionQuality.POOR -> POOR
                LivekitModels.ConnectionQuality.UNRECOGNIZED -> UNKNOWN
            }
        }
    }
}