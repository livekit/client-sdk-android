package io.livekit.android.room.participant

import io.livekit.android.room.track.*
import livekit.LivekitModels
import java.nio.ByteBuffer

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
    var dataTracks = mutableMapOf<String, TrackPublication>()
        private set

    /**
     * @suppress
     */
    fun addTrackPublication(publication: TrackPublication) {
        val track = publication.track
        track?.sid = publication.sid
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
            listener?.onMetadataChanged(this, prevMetadata)
            internalListener?.onMetadataChanged(this, prevMetadata)
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


interface ParticipantListener {
    /**
     * When a participant's metadata is updated, fired for all participants
     */
    fun onMetadataChanged(participant: Participant, prevMetadata: String?) {}

    /**
     * Fired when the current participant's isSpeaking property changes. (including LocalParticipant)
     */
    fun onSpeakingChanged(participant: Participant) {}

    fun onTrackPublished(publication: TrackPublication, participant: RemoteParticipant) {}
    fun onTrackUnpublished(publication: TrackPublication, participant: RemoteParticipant) {}

    fun onEnable(publication: TrackPublication, participant: RemoteParticipant) {}
    fun onDisable(publication: TrackPublication, participant: RemoteParticipant) {}

    fun onTrackSubscribed(track: Track, publication: TrackPublication, participant: RemoteParticipant) {}
    fun onTrackSubscriptionFailed(
        sid: String,
        exception: Exception,
        participant: RemoteParticipant
    ) {
    }

    fun onTrackUnsubscribed(
        track: Track,
        publication: TrackPublication,
        participant: RemoteParticipant
    ) {
    }

    fun onDataReceived(
        data: ByteBuffer,
        dataTrack: DataTrack,
        participant: RemoteParticipant
    ) {
    }

    fun switchedOffVideo(track: VideoTrack, publication: TrackPublication, participant: RemoteParticipant) {}
    fun switchedOnVideo(track: VideoTrack, publication: TrackPublication, participant: RemoteParticipant) {}
}