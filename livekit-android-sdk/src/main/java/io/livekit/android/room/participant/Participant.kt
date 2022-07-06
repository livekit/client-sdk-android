package io.livekit.android.room.participant

import io.livekit.android.dagger.InjectionNames
import io.livekit.android.events.BroadcastEventBus
import io.livekit.android.events.ParticipantEvent
import io.livekit.android.events.TrackEvent
import io.livekit.android.room.track.LocalTrackPublication
import io.livekit.android.room.track.RemoteTrackPublication
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.TrackPublication
import io.livekit.android.util.FlowObservable
import io.livekit.android.util.flow
import io.livekit.android.util.flowDelegate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import livekit.LivekitModels
import javax.inject.Named

open class Participant(
    var sid: String,
    identity: String? = null,
    @Named(InjectionNames.DISPATCHER_DEFAULT)
    private val coroutineDispatcher: CoroutineDispatcher,
) {

    /**
     * To only be used for flow delegate scoping, and should not be cancelled.
     **/
    private val delegateScope = createScope()
    protected var scope: CoroutineScope = createScope()
        private set

    private fun createScope() = CoroutineScope(coroutineDispatcher + SupervisorJob())

    protected val eventBus = BroadcastEventBus<ParticipantEvent>()
    val events = eventBus.readOnly()

    /**
     * Changes can be observed by using [io.livekit.android.util.flow]
     */
    @FlowObservable
    @get:FlowObservable
    var participantInfo: LivekitModels.ParticipantInfo? by flowDelegate(null)
        private set

    /**
     * Changes can be observed by using [io.livekit.android.util.flow]
     */
    @FlowObservable
    @get:FlowObservable
    var identity: String? by flowDelegate(identity)
        internal set

    /**
     * Changes can be observed by using [io.livekit.android.util.flow]
     */
    @FlowObservable
    @get:FlowObservable
    var audioLevel: Float by flowDelegate(0f)
        internal set

    /**
     * Changes can be observed by using [io.livekit.android.util.flow]
     */
    @FlowObservable
    @get:FlowObservable
    var isSpeaking: Boolean by flowDelegate(false) { newValue, oldValue ->
        if (newValue != oldValue) {
            listener?.onSpeakingChanged(this)
            internalListener?.onSpeakingChanged(this)
            eventBus.postEvent(ParticipantEvent.SpeakingChanged(this, newValue), scope)
        }
    }
        internal set

    @FlowObservable
    @get:FlowObservable
    var name by flowDelegate<String?>(null)

    /**
     * Changes can be observed by using [io.livekit.android.util.flow]
     */
    @FlowObservable
    @get:FlowObservable
    var metadata: String? by flowDelegate(null) { newMetadata, oldMetadata ->
        if (newMetadata != oldMetadata) {
            listener?.onMetadataChanged(this, oldMetadata)
            internalListener?.onMetadataChanged(this, oldMetadata)
            eventBus.postEvent(ParticipantEvent.MetadataChanged(this, oldMetadata), scope)
        }
    }
        internal set

    /**
     *
     */
    @FlowObservable
    @get:FlowObservable
    var permissions: ParticipantPermission? by flowDelegate(null) { newPermissions, oldPermissions ->
        if (newPermissions != oldPermissions) {
            eventBus.postEvent(
                ParticipantEvent.ParticipantPermissionsChanged(
                    this,
                    newPermissions,
                    oldPermissions
                ),
                scope
            )
        }
    }
        internal set

    /**
     * Changes can be observed by using [io.livekit.android.util.flow]
     */
    @FlowObservable
    @get:FlowObservable
    var connectionQuality by flowDelegate(ConnectionQuality.UNKNOWN)
        internal set

    /**
     * Listener for when participant properties change
     */
    @Deprecated("Use events instead")
    var listener: ParticipantListener? = null

    /**
     * @suppress
     */
    @Deprecated("Use events instead")
    internal var internalListener: ParticipantListener? = null

    val hasInfo
        get() = participantInfo != null

    /**
     * Changes can be observed by using [io.livekit.android.util.flow]
     */
    @FlowObservable
    @get:FlowObservable
    var tracks by flowDelegate(emptyMap<String, TrackPublication>())
        protected set

    private fun Flow<Map<String, TrackPublication>>.trackUpdateFlow(): Flow<List<Pair<TrackPublication, Track?>>> {
        return flatMapLatest { videoTracks ->
            combine(
                videoTracks.values
                    .map { trackPublication ->
                        // Re-emit when track changes
                        trackPublication::track.flow
                            .map { trackPublication to trackPublication.track }
                    }
            ) { trackPubs ->
                trackPubs.toList()
            }
        }
    }

    /**
     * Changes can be observed by using [io.livekit.android.util.flow]
     */
    @FlowObservable
    @get:FlowObservable
    val audioTracks by flowDelegate(
        stateFlow = ::tracks.flow
            .map { it.filterValues { publication -> publication.kind == Track.Kind.AUDIO } }
            .trackUpdateFlow()
            .stateIn(delegateScope, SharingStarted.Eagerly, emptyList())
    )

    /**
     * Changes can be observed by using [io.livekit.android.util.flow]
     */
    @FlowObservable
    @get:FlowObservable
    val videoTracks by flowDelegate(
        stateFlow = ::tracks.flow
            .map { it.filterValues { publication -> publication.kind == Track.Kind.VIDEO } }
            .trackUpdateFlow()
            .stateIn(delegateScope, SharingStarted.Eagerly, emptyList())
    )

    /**
     * @suppress
     */
    fun addTrackPublication(publication: TrackPublication) {
        val track = publication.track
        track?.sid = publication.sid
        tracks = tracks.toMutableMap().apply {
            this[publication.sid] = publication
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

    fun isCameraEnabled(): Boolean {
        val pub = getTrackPublication(Track.Source.CAMERA)
        return isTrackPublicationEnabled(pub)
    }

    fun isMicrophoneEnabled(): Boolean {
        val pub = getTrackPublication(Track.Source.MICROPHONE)
        return isTrackPublicationEnabled(pub)
    }

    fun isScreenShareEnabled(): Boolean {
        val pub = getTrackPublication(Track.Source.SCREEN_SHARE)
        return isTrackPublicationEnabled(pub)
    }

    private fun isTrackPublicationEnabled(pub: TrackPublication?): Boolean {
        return !(pub?.muted ?: true)
    }

    /**
     * @suppress
     */
    internal open fun updateFromInfo(info: LivekitModels.ParticipantInfo) {
        sid = info.sid
        identity = info.identity
        participantInfo = info
        metadata = info.metadata
        name = info.name
        if (info.hasPermission()) {
            permissions = ParticipantPermission.fromProto(info.permission)
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

    // Internal methods just for posting events.
    internal fun onTrackMuted(trackPublication: TrackPublication) {
        listener?.onTrackMuted(trackPublication, this)
        internalListener?.onTrackMuted(trackPublication, this)
        eventBus.postEvent(ParticipantEvent.TrackMuted(this, trackPublication), scope)
    }

    internal fun onTrackUnmuted(trackPublication: TrackPublication) {
        listener?.onTrackUnmuted(trackPublication, this)
        internalListener?.onTrackUnmuted(trackPublication, this)
        eventBus.postEvent(ParticipantEvent.TrackUnmuted(this, trackPublication), scope)
    }

    internal fun onTrackStreamStateChanged(trackEvent: TrackEvent.StreamStateChanged) {
        val trackPublication = tracks[trackEvent.track.sid] ?: return
        eventBus.postEvent(
            ParticipantEvent.TrackStreamStateChanged(this, trackPublication, trackEvent.streamState),
            scope
        )
    }

    internal fun reinitialize() {
        if (!scope.isActive) {
            scope = createScope()
        }
    }

    internal open fun dispose() {
        scope.cancel()
    }
}

@Deprecated("Use Participant.events instead.")
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

data class ParticipantPermission(
    val canPublish: Boolean,
    val canSubscribe: Boolean,
    val canPublishData: Boolean,
    val hidden: Boolean,
    val recorder: Boolean,
) {
    companion object {
        fun fromProto(proto: LivekitModels.ParticipantPermission): ParticipantPermission {
            return ParticipantPermission(
                canPublish = proto.canPublish,
                canSubscribe = proto.canSubscribe,
                canPublishData = proto.canPublishData,
                hidden = proto.hidden,
                recorder = proto.recorder,
            )
        }
    }
}