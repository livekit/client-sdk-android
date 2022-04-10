package io.livekit.android.events

import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.ParticipantPermission
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.LocalTrackPublication
import io.livekit.android.room.track.RemoteTrackPublication
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.TrackPublication

sealed class ParticipantEvent(open val participant: Participant) : Event() {
    // all participants
    /**
     * When a participant's metadata is updated, fired for all participants
     */
    class MetadataChanged(participant: Participant, val prevMetadata: String?) : ParticipantEvent(participant)

    /**
     * Fired when the current participant's isSpeaking property changes. (including LocalParticipant)
     */
    class SpeakingChanged(participant: Participant, val isSpeaking: Boolean) : ParticipantEvent(participant)

    /**
     * The participant was muted.
     *
     * For the local participant, the callback will be called if setMute was called on the
     * [LocalTrackPublication], or if the server has requested the participant to be muted
     */
    class TrackMuted(participant: Participant, val publication: TrackPublication) : ParticipantEvent(participant)

    /**
     * The participant was unmuted.
     *
     * For the local participant, the callback will be called if setMute was called on the
     * [LocalTrackPublication], or if the server has requested the participant to be muted
     */
    class TrackUnmuted(participant: Participant, val publication: TrackPublication) : ParticipantEvent(participant)


    // local participants
    /**
     * When a new track is published by the local participant.
     */
    class LocalTrackPublished(override val participant: LocalParticipant, val publication: LocalTrackPublication) :
        ParticipantEvent(participant)

    /**
     * A [LocalParticipant] has unpublished a track
     */
    class LocalTrackUnpublished(override val participant: LocalParticipant, val publication: LocalTrackPublication) :
        ParticipantEvent(participant)

    // remote participants
    /**
     * When a new track is published to room after the local participant has joined.
     *
     * It will not fire for tracks that are already published
     */
    class TrackPublished(override val participant: RemoteParticipant, val publication: RemoteTrackPublication) :
        ParticipantEvent(participant)

    /**
     * A [RemoteParticipant] has unpublished a track
     */
    class TrackUnpublished(override val participant: RemoteParticipant, val publication: RemoteTrackPublication) :
        ParticipantEvent(participant)

    /**
     * Subscribed to a new track
     */
    class TrackSubscribed(
        override val participant: RemoteParticipant,
        val track: Track,
        val publication: RemoteTrackPublication,
    ) :
        ParticipantEvent(participant)

    /**
     * Error had occurred while subscribing to a track
     */
    class TrackSubscriptionFailed(
        override val participant: RemoteParticipant,
        val sid: String,
        val exception: Exception,
    ) : ParticipantEvent(participant)

    /**
     * A subscribed track is no longer available.
     * Clients should listen to this event and handle cleanup
     */
    class TrackUnsubscribed(
        override val participant: RemoteParticipant,
        val track: Track,
        val publication: RemoteTrackPublication
    ) : ParticipantEvent(participant)

    /**
     * Received data published by another participant
     */
    class DataReceived(override val participant: RemoteParticipant, val data: ByteArray) : ParticipantEvent(participant)

    /**
     * A track's stream state has changed.
     */
    class TrackStreamStateChanged(
        override val participant: Participant,
        val trackPublication: TrackPublication,
        val streamState: Track.StreamState
    ) : ParticipantEvent(participant)


    /**
     * A remote track's subscription permissions have changed.
     */
    class TrackSubscriptionPermissionChanged(
        override val participant: RemoteParticipant,
        val trackPublication: RemoteTrackPublication,
        val subscriptionAllowed: Boolean
    ) : ParticipantEvent(participant)

    /**
     * A participant's permissions have changed.
     *
     * Currently only fires for the local participant.
     */
    class ParticipantPermissionsChanged(
        override val participant: Participant,
        val newPermissions: ParticipantPermission?,
        val oldPermissions: ParticipantPermission?,
    ) : ParticipantEvent(participant)
}