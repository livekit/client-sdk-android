@file:Suppress("unused")

package io.livekit.android.room

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import io.livekit.android.ConnectOptions
import io.livekit.android.RoomOptions
import io.livekit.android.Version
import io.livekit.android.audio.AudioHandler
import io.livekit.android.dagger.InjectionNames
import io.livekit.android.events.BroadcastEventBus
import io.livekit.android.events.ParticipantEvent
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.participant.*
import io.livekit.android.room.track.*
import io.livekit.android.util.FlowObservable
import io.livekit.android.util.LKLog
import io.livekit.android.util.flowDelegate
import io.livekit.android.util.invoke
import kotlinx.coroutines.*
import livekit.LivekitModels
import livekit.LivekitRtc
import org.webrtc.*
import javax.inject.Named

class Room
@AssistedInject
constructor(
    @Assisted private val context: Context,
    private val engine: RTCEngine,
    private val eglBase: EglBase,
    private val localParticipantFactory: LocalParticipant.Factory,
    private val defaultsManager: DefaultsManager,
    @Named(InjectionNames.DISPATCHER_DEFAULT)
    private val defaultDispatcher: CoroutineDispatcher,
    @Named(InjectionNames.DISPATCHER_IO)
    private val ioDispatcher: CoroutineDispatcher,
    val audioHandler: AudioHandler,
) : RTCEngine.Listener, ParticipantListener, ConnectivityManager.NetworkCallback() {

    private lateinit var coroutineScope: CoroutineScope
    private val eventBus = BroadcastEventBus<RoomEvent>()
    val events = eventBus.readOnly()

    init {
        engine.listener = this
    }

    enum class State {
        CONNECTING,
        CONNECTED,
        DISCONNECTED,
        RECONNECTING;
    }

    @JvmInline
    value class Sid(val sid: String)

    @Deprecated("Use events instead.")
    var listener: RoomListener? = null

    @FlowObservable
    @get:FlowObservable
    var sid: Sid? by flowDelegate(null)

    @FlowObservable
    @get:FlowObservable
    var name: String? by flowDelegate(null)
        private set

    @FlowObservable
    @get:FlowObservable
    var state: State by flowDelegate(State.DISCONNECTED) { new, old ->
        if (new != old) {
            when (new) {
                State.CONNECTING -> audioHandler.start()
                State.DISCONNECTED -> audioHandler.stop()
                else -> {}
            }
        }
    }
        private set

    @FlowObservable
    @get:FlowObservable
    var metadata: String? by flowDelegate(null)
        private set

    /**
     * Automatically manage quality of subscribed video tracks, subscribe to the
     * an appropriate resolution based on the size of the video elements that tracks
     * are attached to.
     *
     * Also observes the visibility of attached tracks and pauses receiving data
     * if they are not visible.
     *
     * Defaults to false.
     */
    var adaptiveStream: Boolean = false

    /**
     * Dynamically pauses video layers that are not being consumed by any subscribers,
     * significantly reducing publishing CPU and bandwidth usage.
     *
     * Defaults to false.
     */
    var dynacast: Boolean = false

    /**
     * Default options to use when creating an audio track.
     */
    var audioTrackCaptureDefaults: LocalAudioTrackOptions by defaultsManager::audioTrackCaptureDefaults

    /**
     * Default options to use when publishing an audio track.
     */
    var audioTrackPublishDefaults: AudioTrackPublishDefaults by defaultsManager::audioTrackPublishDefaults

    /**
     * Default options to use when creating a video track.
     */
    var videoTrackCaptureDefaults: LocalVideoTrackOptions by defaultsManager::videoTrackCaptureDefaults

    /**
     * Default options to use when publishing a video track.
     */
    var videoTrackPublishDefaults: VideoTrackPublishDefaults by defaultsManager::videoTrackPublishDefaults

    val localParticipant: LocalParticipant = localParticipantFactory.create(dynacast = dynacast).apply {
        internalListener = this@Room
    }

    private var mutableRemoteParticipants by flowDelegate(emptyMap<String, RemoteParticipant>())

    @FlowObservable
    @get:FlowObservable
    val remoteParticipants: Map<String, RemoteParticipant>
        get() = mutableRemoteParticipants

    private var mutableActiveSpeakers by flowDelegate(emptyList<Participant>())

    @FlowObservable
    @get:FlowObservable
    val activeSpeakers: List<Participant>
        get() = mutableActiveSpeakers

    private var hasLostConnectivity: Boolean = false
    private var connectOptions: ConnectOptions = ConnectOptions()

    private fun getCurrentRoomOptions(): RoomOptions =
        RoomOptions(
            adaptiveStream = adaptiveStream,
            dynacast = dynacast,
            audioTrackCaptureDefaults = audioTrackCaptureDefaults,
            videoTrackCaptureDefaults = videoTrackCaptureDefaults,
            audioTrackPublishDefaults = audioTrackPublishDefaults,
            videoTrackPublishDefaults = videoTrackPublishDefaults,
        )

    suspend fun connect(url: String, token: String, options: ConnectOptions = ConnectOptions()) {
        if (this::coroutineScope.isInitialized) {
            coroutineScope.cancel()
        }
        coroutineScope = CoroutineScope(defaultDispatcher + SupervisorJob())

        // Setup local participant.
        localParticipant.reinitialize()
        coroutineScope.launch {
            localParticipant.events.collect {
                when (it) {
                    is ParticipantEvent.TrackPublished -> emitWhenConnected(
                        RoomEvent.TrackPublished(
                            room = this@Room,
                            publication = it.publication,
                            participant = it.participant,
                        )
                    )
                    is ParticipantEvent.ParticipantPermissionsChanged -> emitWhenConnected(
                        RoomEvent.ParticipantPermissionsChanged(
                            room = this@Room,
                            participant = it.participant,
                            newPermissions = it.newPermissions,
                            oldPermissions = it.oldPermissions,
                        )
                    )
                    is ParticipantEvent.MetadataChanged -> {
                        listener?.onMetadataChanged(it.participant, it.prevMetadata, this@Room)
                        emitWhenConnected(
                            RoomEvent.ParticipantMetadataChanged(
                                this@Room,
                                it.participant,
                                it.prevMetadata
                            )
                        )
                    }
                    else -> {
                        /* do nothing */
                    }
                }
            }
        }

        state = State.CONNECTING
        connectOptions = options
        engine.join(url, token, options, getCurrentRoomOptions())

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(networkRequest, this)

        if (options.audio) {
            val audioTrack = localParticipant.createAudioTrack()
            localParticipant.publishAudioTrack(audioTrack)
        }
        if (options.video) {
            val videoTrack = localParticipant.createVideoTrack()
            localParticipant.publishVideoTrack(videoTrack)
        }
    }

    /**
     * Disconnect from the room.
     */
    fun disconnect() {
        engine.client.sendLeave()
        handleDisconnect()
    }

    override fun onJoinResponse(response: LivekitRtc.JoinResponse) {

        LKLog.i { "Connected to server, server version: ${response.serverVersion}, client version: ${Version.CLIENT_VERSION}" }

        sid = Sid(response.room.sid)
        name = response.room.name

        if (!response.hasParticipant()) {
            listener?.onFailedToConnect(this, RoomException.ConnectException("server didn't return any participants"))
            return
        }

        localParticipant.updateFromInfo(response.participant)

        if (response.otherParticipantsList.isNotEmpty()) {
            response.otherParticipantsList.forEach {
                getOrCreateRemoteParticipant(it.sid, it)
            }
        }
    }

    private fun handleParticipantDisconnect(sid: String) {
        val newParticipants = mutableRemoteParticipants.toMutableMap()
        val removedParticipant = newParticipants.remove(sid) ?: return
        removedParticipant.tracks.values.toList().forEach { publication ->
            removedParticipant.unpublishTrack(publication.sid, true)
        }

        mutableRemoteParticipants = newParticipants
        listener?.onParticipantDisconnected(this, removedParticipant)
        eventBus.postEvent(RoomEvent.ParticipantDisconnected(this, removedParticipant), coroutineScope)
    }

    fun getParticipant(sid: String): Participant? {
        if (sid == localParticipant.sid) {
            return localParticipant
        } else {
            return remoteParticipants[sid]
        }
    }

    @Synchronized
    private fun getOrCreateRemoteParticipant(
        sid: String,
        info: LivekitModels.ParticipantInfo? = null
    ): RemoteParticipant {
        var participant = remoteParticipants[sid]
        if (participant != null) {
            return participant
        }

        participant = if (info != null) {
            RemoteParticipant(info, engine.client, ioDispatcher, defaultDispatcher)
        } else {
            RemoteParticipant(sid, null, engine.client, ioDispatcher, defaultDispatcher)
        }
        participant.internalListener = this

        coroutineScope.launch {
            participant.events.collect {
                when (it) {
                    is ParticipantEvent.TrackPublished -> {
                        if (state == State.CONNECTED) {
                            eventBus.postEvent(
                                RoomEvent.TrackPublished(
                                    room = this@Room,
                                    publication = it.publication,
                                    participant = it.participant,
                                )
                            )
                        }
                    }
                    is ParticipantEvent.TrackStreamStateChanged -> eventBus.postEvent(
                        RoomEvent.TrackStreamStateChanged(
                            this@Room,
                            it.trackPublication,
                            it.streamState
                        )
                    )
                    is ParticipantEvent.TrackSubscriptionPermissionChanged -> eventBus.postEvent(
                        RoomEvent.TrackSubscriptionPermissionChanged(
                            this@Room,
                            it.participant,
                            it.trackPublication,
                            it.subscriptionAllowed
                        )
                    )
                    is ParticipantEvent.MetadataChanged -> {
                        listener?.onMetadataChanged(it.participant, it.prevMetadata, this@Room)
                        emitWhenConnected(
                            RoomEvent.ParticipantMetadataChanged(
                                this@Room,
                                it.participant,
                                it.prevMetadata
                            )
                        )
                    }
                    is ParticipantEvent.ParticipantPermissionsChanged -> eventBus.postEvent(
                        RoomEvent.ParticipantPermissionsChanged(
                            room = this@Room,
                            participant = it.participant,
                            newPermissions = it.newPermissions,
                            oldPermissions = it.oldPermissions,
                        )
                    )
                    else -> {
                        /* do nothing */
                    }
                }
            }
        }

        if (info != null) {
            participant.updateFromInfo(info)
        }

        val newRemoteParticipants = mutableRemoteParticipants.toMutableMap()
        newRemoteParticipants[sid] = participant
        mutableRemoteParticipants = newRemoteParticipants

        return participant
    }

    private fun handleActiveSpeakersUpdate(speakerInfos: List<LivekitModels.SpeakerInfo>) {
        val speakers = mutableListOf<Participant>()
        val seenSids = mutableSetOf<String>()
        val localParticipant = localParticipant
        speakerInfos.forEach { speakerInfo ->
            val speakerSid = speakerInfo.sid!!
            seenSids.add(speakerSid)

            val participant = getParticipant(speakerSid) ?: return@forEach
            participant.audioLevel = speakerInfo.level
            participant.isSpeaking = true
            speakers.add(participant)
        }

        if (!seenSids.contains(localParticipant.sid)) {
            localParticipant.audioLevel = 0.0f
            localParticipant.isSpeaking = false
        }
        remoteParticipants.values
            .filterNot { seenSids.contains(it.sid) }
            .forEach {
                it.audioLevel = 0.0f
                it.isSpeaking = false
            }

        mutableActiveSpeakers = speakers.toList()
        listener?.onActiveSpeakersChanged(mutableActiveSpeakers, this)
        eventBus.postEvent(RoomEvent.ActiveSpeakersChanged(this, mutableActiveSpeakers), coroutineScope)
    }

    private fun handleSpeakersChanged(speakerInfos: List<LivekitModels.SpeakerInfo>) {
        val updatedSpeakers = mutableMapOf<String, Participant>()
        activeSpeakers.forEach {
            updatedSpeakers[it.sid] = it
        }

        speakerInfos.forEach { speaker ->
            val participant = getParticipant(speaker.sid) ?: return@forEach

            participant.audioLevel = speaker.level
            participant.isSpeaking = speaker.active

            if (speaker.active) {
                updatedSpeakers[speaker.sid] = participant
            } else {
                updatedSpeakers.remove(speaker.sid)
            }
        }

        val updatedSpeakersList = updatedSpeakers.values.toList()
            .sortedBy { it.audioLevel }

        mutableActiveSpeakers = updatedSpeakersList.toList()
        listener?.onActiveSpeakersChanged(mutableActiveSpeakers, this)
        eventBus.postEvent(RoomEvent.ActiveSpeakersChanged(this, mutableActiveSpeakers), coroutineScope)
    }

    private fun reconnect() {
        if (state == State.RECONNECTING) {
            return
        }
        engine.reconnect()
    }

    /**
     * Removes all participants and tracks from the room.
     */
    private fun cleanupRoom() {
        localParticipant.cleanup()
        remoteParticipants.keys.toMutableSet()  // copy keys to avoid concurrent modifications.
            .forEach { sid -> handleParticipantDisconnect(sid) }
    }

    private fun handleDisconnect() {
        if (state == State.DISCONNECTED) {
            return
        }

        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(this)
        } catch (e: IllegalArgumentException) {
            // do nothing, may happen on older versions if attempting to unregister twice.
        }

        state = State.DISCONNECTED
        engine.close()
        cleanupRoom()

        listener?.onDisconnect(this, null)
        listener = null
        localParticipant.dispose()

        // Ensure all observers see the disconnected before closing scope.
        runBlocking {
            eventBus.postEvent(RoomEvent.Disconnected(this@Room, null), coroutineScope).join()
        }
        coroutineScope.cancel()
    }

    private fun sendSyncState() {
        // Whether we're sending subscribed tracks or tracks to unsubscribe.
        val sendUnsub = connectOptions.autoSubscribe
        val participantTracksList = mutableListOf<LivekitModels.ParticipantTracks>()
        for (participant in remoteParticipants.values) {
            val builder = LivekitModels.ParticipantTracks.newBuilder()
            builder.participantSid = participant.sid
            for (trackPub in participant.tracks.values) {
                val remoteTrackPub = (trackPub as? RemoteTrackPublication) ?: continue
                if (remoteTrackPub.subscribed != sendUnsub) {
                    builder.addTrackSids(remoteTrackPub.sid)
                }
            }

            if (builder.trackSidsCount > 0) {
                participantTracksList.add(builder.build())
            }
        }

        // backwards compatibility for protocol version < 6
        val trackSids = participantTracksList.map { it.trackSidsList }
            .flatten()

        val subscription = LivekitRtc.UpdateSubscription.newBuilder()
            .setSubscribe(!sendUnsub)
            .addAllParticipantTracks(participantTracksList)
            .addAllTrackSids(trackSids)
            .build()
        val publishedTracks = localParticipant.publishTracksInfo()
        engine.sendSyncState(subscription, publishedTracks)
    }

    /**
     * Sends a simulated scenario for the server to use.
     *
     * To be used for internal testing purposes only.
     * @suppress
     */
    fun sendSimulateScenario(scenario: LivekitRtc.SimulateScenario) {
        engine.client.sendSimulateScenario(scenario)
    }

    /**
     * @suppress
     */
    @AssistedFactory
    interface Factory {
        fun create(context: Context): Room
    }

    //------------------------------------- NetworkCallback -------------------------------------//

    /**
     * @suppress
     */
    override fun onLost(network: Network) {
        // lost connection, flip to reconnecting
        hasLostConnectivity = true
    }

    /**
     * @suppress
     */
    override fun onAvailable(network: Network) {
        // only actually reconnect after connection is re-established
        if (!hasLostConnectivity) {
            return
        }
        LKLog.i { "network connection available, reconnecting" }
        reconnect()
        hasLostConnectivity = false
    }


    //----------------------------------- RTCEngine.Listener ------------------------------------//

    /**
     * @suppress
     */
    override fun onEngineConnected() {
        state = State.CONNECTED
    }

    /**
     * @suppress
     */
    override fun onEngineReconnected() {
        state = State.CONNECTED
        listener?.onReconnected(this)
        eventBus.postEvent(RoomEvent.Reconnected(this), coroutineScope)
    }

    /**
     * @suppress
     */
    override fun onEngineReconnecting() {
        state = State.RECONNECTING
        listener?.onReconnecting(this)
        eventBus.postEvent(RoomEvent.Reconnecting(this), coroutineScope)
    }

    /**
     * @suppress
     */
    override fun onAddTrack(track: MediaStreamTrack, streams: Array<out MediaStream>) {
        if (streams.count() < 0) {
            LKLog.i { "add track with empty streams?" }
            return
        }

        var (participantSid, trackSid) = unpackStreamId(streams.first().id)
        if (trackSid == null) {
            trackSid = track.id()
        }
        val participant = getOrCreateRemoteParticipant(participantSid)
        participant.addSubscribedMediaTrack(track, trackSid!!, adaptiveStream)
    }

    /**
     * @suppress
     */
    override fun onUpdateParticipants(updates: List<LivekitModels.ParticipantInfo>) {
        for (info in updates) {
            val participantSid = info.sid

            if (localParticipant.sid == participantSid) {
                localParticipant.updateFromInfo(info)
                continue
            }

            val isNewParticipant = !remoteParticipants.contains(participantSid)
            val participant = getOrCreateRemoteParticipant(participantSid, info)

            if (info.state == LivekitModels.ParticipantInfo.State.DISCONNECTED) {
                handleParticipantDisconnect(participantSid)
            } else if (isNewParticipant) {
                listener?.onParticipantConnected(this, participant)
                eventBus.postEvent(RoomEvent.ParticipantConnected(this, participant), coroutineScope)
            } else {
                participant.updateFromInfo(info)
            }
        }
    }

    /**
     * @suppress
     */
    override fun onActiveSpeakersUpdate(speakers: List<LivekitModels.SpeakerInfo>) {
        handleActiveSpeakersUpdate(speakers)
    }

    /**
     * @suppress
     */
    override fun onRemoteMuteChanged(trackSid: String, muted: Boolean) {
        localParticipant.onRemoteMuteChanged(trackSid, muted)
    }

    /**
     * @suppress
     */
    override fun onRoomUpdate(update: LivekitModels.Room) {
        val oldMetadata = metadata
        metadata = update.metadata

        eventBus.postEvent(RoomEvent.RoomMetadataChanged(this, metadata, oldMetadata), coroutineScope)
    }

    /**
     * @suppress
     */
    override fun onConnectionQuality(updates: List<LivekitRtc.ConnectionQualityInfo>) {
        updates.forEach { info ->
            val quality = ConnectionQuality.fromProto(info.quality)
            val participant = getParticipant(info.participantSid) ?: return
            participant.connectionQuality = quality
            listener?.onConnectionQualityChanged(participant, quality)
            eventBus.postEvent(RoomEvent.ConnectionQualityChanged(this, participant, quality), coroutineScope)
        }
    }

    /**
     * @suppress
     */
    override fun onSpeakersChanged(speakers: List<LivekitModels.SpeakerInfo>) {
        handleSpeakersChanged(speakers)
    }

    /**
     * @suppress
     */
    override fun onUserPacket(packet: LivekitModels.UserPacket, kind: LivekitModels.DataPacket.Kind) {
        val participant = remoteParticipants[packet.participantSid] ?: return
        val data = packet.payload.toByteArray()

        listener?.onDataReceived(data, participant, this)
        eventBus.postEvent(RoomEvent.DataReceived(this, data, participant), coroutineScope)
        participant.onDataReceived(data)
    }

    /**
     * @suppress
     */
    override fun onStreamStateUpdate(streamStates: List<LivekitRtc.StreamStateInfo>) {
        for (streamState in streamStates) {
            val participant = getParticipant(streamState.participantSid) ?: continue
            val track = participant.tracks[streamState.trackSid] ?: continue

            track.track?.streamState = Track.StreamState.fromProto(streamState.state)
        }
    }

    /**
     * @suppress
     */
    override fun onSubscribedQualityUpdate(subscribedQualityUpdate: LivekitRtc.SubscribedQualityUpdate) {
        localParticipant.handleSubscribedQualityUpdate(subscribedQualityUpdate)
    }

    /**
     * @suppress
     */
    override fun onSubscriptionPermissionUpdate(subscriptionPermissionUpdate: LivekitRtc.SubscriptionPermissionUpdate) {
        val participant = getParticipant(subscriptionPermissionUpdate.participantSid) as? RemoteParticipant ?: return
        participant.onSubscriptionPermissionUpdate(subscriptionPermissionUpdate)
    }

    /**
     * @suppress
     */
    override fun onEngineDisconnected(reason: String) {
        LKLog.v { "engine did disconnect: $reason" }
        handleDisconnect()
    }

    /**
     * @suppress
     */
    override fun onFailToConnect(error: Throwable) {
        listener?.onFailedToConnect(this, error)
        // scope will likely be closed already here, so force it out of scope.
        eventBus.tryPostEvent(RoomEvent.FailedToConnect(this, error))
    }

    /**
     * @suppress
     */
    override fun onSignalConnected(isResume: Boolean) {
        if (state == State.RECONNECTING && isResume) {
            // during resume reconnection, need to send sync state upon signal connection.
            sendSyncState()
        }
    }

    /**
     * @suppress
     */
    override fun onFullReconnecting() {
        localParticipant.prepareForFullReconnect()
        remoteParticipants.keys.toMutableSet()  // copy keys to avoid concurrent modifications.
            .forEach { sid -> handleParticipantDisconnect(sid) }
    }

    /**
     * @suppress
     */
    override suspend fun onPostReconnect(isFullReconnect: Boolean) {
        if (isFullReconnect) {
            localParticipant.republishTracks()
        } else {
            val remoteParticipants = remoteParticipants.values.toList()
            for (participant in remoteParticipants) {
                val pubs = participant.tracks.values.toList()
                for (pub in pubs) {
                    val remotePub = pub as? RemoteTrackPublication ?: continue
                    if (remotePub.subscribed) {
                        remotePub.sendUpdateTrackSettings.invoke()
                    }
                }
            }
        }
    }

    /**
     * @suppress
     */
    override fun onLocalTrackUnpublished(trackUnpublished: LivekitRtc.TrackUnpublishedResponse) {
        localParticipant.handleLocalTrackUnpublished(trackUnpublished)
    }

    //------------------------------- ParticipantListener --------------------------------//
    /**
     * This is called for both Local and Remote participants
     * @suppress
     */
    override fun onMetadataChanged(participant: Participant, prevMetadata: String?) {
    }

    /** @suppress */
    override fun onTrackMuted(publication: TrackPublication, participant: Participant) {
        listener?.onTrackMuted(publication, participant, this)
        eventBus.postEvent(RoomEvent.TrackMuted(this, publication, participant), coroutineScope)
    }

    /** @suppress */
    override fun onTrackUnmuted(publication: TrackPublication, participant: Participant) {
        listener?.onTrackUnmuted(publication, participant, this)
        eventBus.postEvent(RoomEvent.TrackUnmuted(this, publication, participant), coroutineScope)
    }

    /**
     * @suppress
     */
    override fun onTrackUnpublished(publication: RemoteTrackPublication, participant: RemoteParticipant) {
        listener?.onTrackUnpublished(publication, participant, this)
        eventBus.postEvent(RoomEvent.TrackUnpublished(this, publication, participant), coroutineScope)
    }

    /**
     * @suppress
     */
    override fun onTrackUnpublished(publication: LocalTrackPublication, participant: LocalParticipant) {
        listener?.onTrackUnpublished(publication, participant, this)
        eventBus.postEvent(RoomEvent.TrackUnpublished(this, publication, participant), coroutineScope)
    }

    /**
     * @suppress
     */
    override fun onTrackSubscribed(track: Track, publication: RemoteTrackPublication, participant: RemoteParticipant) {
        listener?.onTrackSubscribed(track, publication, participant, this)
        eventBus.postEvent(RoomEvent.TrackSubscribed(this, track, publication, participant), coroutineScope)
    }

    /**
     * @suppress
     */
    override fun onTrackSubscriptionFailed(
        sid: String,
        exception: Exception,
        participant: RemoteParticipant
    ) {
        listener?.onTrackSubscriptionFailed(sid, exception, participant, this)
        eventBus.postEvent(RoomEvent.TrackSubscriptionFailed(this, sid, exception, participant), coroutineScope)
    }

    /**
     * @suppress
     */
    override fun onTrackUnsubscribed(
        track: Track,
        publication: RemoteTrackPublication,
        participant: RemoteParticipant
    ) {
        listener?.onTrackUnsubscribed(track, publication, participant, this)
        eventBus.postEvent(RoomEvent.TrackUnsubscribed(this, track, publication, participant), coroutineScope)
    }

    /**
     * @suppress
     * // TODO(@dl): can this be moved out of Room/SDK?
     */
    fun initVideoRenderer(viewRenderer: SurfaceViewRenderer) {
        viewRenderer.init(eglBase.eglBaseContext, null)
        viewRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        viewRenderer.setEnableHardwareScaler(false /* enabled */)
    }

    /**
     * @suppress
     * // TODO(@dl): can this be moved out of Room/SDK?
     */
    fun initVideoRenderer(viewRenderer: TextureViewRenderer) {
        viewRenderer.init(eglBase.eglBaseContext, null)
        viewRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        viewRenderer.setEnableHardwareScaler(false /* enabled */)
    }

    private suspend fun emitWhenConnected(event: RoomEvent) {
        if (state == State.CONNECTED) {
            eventBus.postEvent(event)
        }
    }
}

/**
 * Room Listener, this class provides callbacks that clients should override.
 *
 */
@Deprecated("Use Room.events instead")
interface RoomListener {
    /**
     * A network change has been detected and LiveKit attempts to reconnect to the room
     * When reconnect attempts succeed, the room state will be kept, including tracks that are subscribed/published
     */
    fun onReconnecting(room: Room) {}

    /**
     * The reconnect attempt had been successful
     */
    fun onReconnected(room: Room) {}

    /**
     * Disconnected from room
     */
    fun onDisconnect(room: Room, error: Exception?) {}

    /**
     * When a [RemoteParticipant] joins after the local participant. It will not emit events
     * for participants that are already in the room
     */
    fun onParticipantConnected(room: Room, participant: RemoteParticipant) {}

    /**
     * When a [RemoteParticipant] leaves after the local participant has joined.
     */
    fun onParticipantDisconnected(room: Room, participant: RemoteParticipant) {}

    /**
     * Could not connect to the room
     */
    fun onFailedToConnect(room: Room, error: Throwable) {}
//        fun onReconnecting(room: Room, error: Exception) {}
//        fun onReconnect(room: Room) {}

    /**
     * Active speakers changed. List of speakers are ordered by their audio level. loudest
     * speakers first. This will include the [LocalParticipant] too.
     */
    fun onActiveSpeakersChanged(speakers: List<Participant>, room: Room) {}

    // Participant callbacks
    /**
     * Participant metadata is a simple way for app-specific state to be pushed to all users.
     * When RoomService.UpdateParticipantMetadata is called to change a participant's state,
     * this event will be fired for all clients in the room.
     */
    fun onMetadataChanged(participant: Participant, prevMetadata: String?, room: Room) {}

    /**
     * The participant was muted.
     *
     * For the local participant, the callback will be called if setMute was called on the
     * [LocalTrackPublication], or if the server has requested the participant to be muted
     */
    fun onTrackMuted(publication: TrackPublication, participant: Participant, room: Room) {}

    /**
     * The participant was unmuted.
     *
     * For the local participant, the callback will be called if setMute was called on the
     * [LocalTrackPublication], or if the server has requested the participant to be muted
     */
    fun onTrackUnmuted(publication: TrackPublication, participant: Participant, room: Room) {}

    /**
     * When a new track is published to room after the local participant has joined. It will
     * not fire for tracks that are already published
     */
    fun onTrackPublished(publication: TrackPublication, participant: RemoteParticipant, room: Room) {}

    /**
     * A [RemoteParticipant] has unpublished a track
     */
    fun onTrackUnpublished(publication: TrackPublication, participant: RemoteParticipant, room: Room) {}

    /**
     * When a new track is published to room after the local participant has joined.
     */
    fun onTrackPublished(publication: LocalTrackPublication, participant: LocalParticipant, room: Room) {}

    /**
     * [LocalParticipant] has unpublished a track
     */
    fun onTrackUnpublished(publication: LocalTrackPublication, participant: LocalParticipant, room: Room) {}

    /**
     * The [LocalParticipant] has subscribed to a new track. This event will always fire as
     * long as new tracks are ready for use.
     */
    fun onTrackSubscribed(track: Track, publication: TrackPublication, participant: RemoteParticipant, room: Room) {}

    /**
     * Could not subscribe to a track
     */
    fun onTrackSubscriptionFailed(sid: String, exception: Exception, participant: RemoteParticipant, room: Room) {}

    /**
     * A subscribed track is no longer available. Clients should listen to this event and ensure
     * the track removes all renderers
     */
    fun onTrackUnsubscribed(track: Track, publications: TrackPublication, participant: RemoteParticipant, room: Room) {}

    /**
     * Received data published by another participant
     */
    fun onDataReceived(data: ByteArray, participant: RemoteParticipant, room: Room) {}

    /**
     * The connection quality for a participant has changed.
     *
     * @param participant Either a remote participant or [Room.localParticipant]
     * @param quality the new connection quality
     */
    fun onConnectionQualityChanged(participant: Participant, quality: ConnectionQuality) {}

    companion object {
        fun getDefaultDevice(kind: DeviceManager.Kind): String? {
            return DeviceManager.getDefaultDevice(kind)
        }

        fun setDefaultDevice(kind: DeviceManager.Kind, deviceId: String?) {
            DeviceManager.setDefaultDevice(kind, deviceId)
        }
    }
}

sealed class RoomException(message: String? = null, cause: Throwable? = null) :
    Exception(message, cause) {
    class ConnectException(message: String? = null, cause: Throwable? = null) :
        RoomException(message, cause)
}

internal fun unpackStreamId(packed: String): Pair<String, String?> {
    val parts = packed.split('|')
    if (parts.size != 2) {
        return Pair(packed, null)
    }
    return Pair(parts[0], parts[1])
}