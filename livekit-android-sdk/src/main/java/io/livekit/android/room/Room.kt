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
import io.livekit.android.Version
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
import io.livekit.android.util.flow
import io.livekit.android.util.flowDelegate
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

    @get:FlowObservable
    var sid: Sid? by flowDelegate(null)

    @get:FlowObservable
    var name: String? by flowDelegate(null)
        private set

    @get:FlowObservable
    var state: State by flowDelegate(State.DISCONNECTED)
        private set

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
     */
    var autoManageVideo: Boolean = false

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

    lateinit var localParticipant: LocalParticipant
        private set

    private var mutableRemoteParticipants by flowDelegate(emptyMap<String, RemoteParticipant>())

    @get:FlowObservable
    val remoteParticipants: Map<String, RemoteParticipant>
        get() = mutableRemoteParticipants

    private var mutableActiveSpeakers by flowDelegate(emptyList<Participant>())

    @get:FlowObservable
    val activeSpeakers: List<Participant>
        get() = mutableActiveSpeakers

    private var hasLostConnectivity: Boolean = false
    suspend fun connect(url: String, token: String, options: ConnectOptions = ConnectOptions()) {
        if (this::coroutineScope.isInitialized) {
            coroutineScope.cancel()
        }
        coroutineScope = CoroutineScope(defaultDispatcher + SupervisorJob())
        state = State.CONNECTING
        val response = engine.join(url, token, options)
        LKLog.i { "Connected to server, server version: ${response.serverVersion}, client version: ${Version.CLIENT_VERSION}" }

        sid = Sid(response.room.sid)
        name = response.room.name

        if (!response.hasParticipant()) {
            listener?.onFailedToConnect(this, RoomException.ConnectException("server didn't return any participants"))
            return
        }

        val lp = localParticipantFactory.create(response.participant)
        lp.internalListener = this
        localParticipant = lp
        if (response.otherParticipantsList.isNotEmpty()) {
            response.otherParticipantsList.forEach {
                getOrCreateRemoteParticipant(it.sid, it)
            }
        }
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

    fun disconnect() {
        engine.client.sendLeave()
        handleDisconnect()
    }

    private fun handleParticipantDisconnect(sid: String) {
        val newParticipants = mutableRemoteParticipants.toMutableMap()
        val removedParticipant = newParticipants.remove(sid) ?: return
        removedParticipant.tracks.values.toList().forEach { publication ->
            removedParticipant.unpublishTrack(publication.sid)
        }

        mutableRemoteParticipants = newParticipants
        listener?.onParticipantDisconnected(this, removedParticipant)
        eventBus.postEvent(RoomEvent.ParticipantDisconnected(this, removedParticipant), coroutineScope)
    }

    fun getParticipant(sid: String): Participant? {
        if(sid == localParticipant.sid){
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
                when(it){
                    is ParticipantEvent.TrackStreamStateChanged -> eventBus.postEvent(RoomEvent.TrackStreamStateChanged(this@Room, it.trackPublication, it.streamState))
                }
            }
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

            if(speaker.active) {
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
        state = State.RECONNECTING
        engine.reconnect()
        listener?.onReconnecting(this)
        eventBus.postEvent(RoomEvent.Reconnecting(this), coroutineScope)
    }

    private fun handleDisconnect() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.unregisterNetworkCallback(this)
        for (pub in localParticipant.tracks.values) {
            pub.track?.stop()
        }
        // stop remote tracks too
        for (p in remoteParticipants.values) {
            for (pub in p.tracks.values) {
                pub.track?.stop()
            }
        }
        engine.close()
        state = State.DISCONNECTED
        listener?.onDisconnect(this, null)
        listener = null

        // Ensure all observers see the disconnected before closing scope.
        runBlocking {
            eventBus.postEvent(RoomEvent.Disconnected(this@Room, null), coroutineScope).join()
        }
        coroutineScope.cancel()
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
    override fun onIceConnected() {
        state = State.CONNECTED
    }

    override fun onIceReconnected() {
        state = State.CONNECTED
        listener?.onReconnected(this)
        eventBus.postEvent(RoomEvent.Reconnected(this), coroutineScope)
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
        participant.addSubscribedMediaTrack(track, trackSid!!, autoManageVideo)
    }

    /**
     * @suppress
     */
    override fun onUpdateParticipants(updates: List<LivekitModels.ParticipantInfo>) {
        for (info in updates) {
            val participantSid = info.sid

            if(localParticipant.sid == participantSid) {
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

    override fun onRemoteMuteChanged(trackSid: String, muted: Boolean) {
        localParticipant.onRemoteMuteChanged(trackSid, muted)
    }

    override fun onRoomUpdate(update: LivekitModels.Room) {
        val oldMetadata = metadata
        metadata = update.metadata

        eventBus.postEvent(RoomEvent.RoomMetadataChanged(this, metadata, oldMetadata), coroutineScope)
    }

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

    override fun onStreamStateUpdate(streamStates: List<LivekitRtc.StreamStateInfo>) {
        for(streamState in streamStates){
            val participant = getParticipant(streamState.participantSid) ?: continue
            val track = participant.tracks[streamState.trackSid] ?: continue

            track.track?.streamState = Track.StreamState.fromProto(streamState.state)
        }
    }

    /**
     * @suppress
     */
    override fun onDisconnect(reason: String) {
        LKLog.v { "engine did disconnect: $reason" }
        handleDisconnect()
    }

    /**
     * @suppress
     */
    override fun onFailToConnect(error: Exception) {
        listener?.onFailedToConnect(this, error)
    }

    //------------------------------- ParticipantListener --------------------------------//
    /**
     * This is called for both Local and Remote participants
     * @suppress
     */
    override fun onMetadataChanged(participant: Participant, prevMetadata: String?) {
        listener?.onMetadataChanged(participant, prevMetadata, this)
        eventBus.postEvent(RoomEvent.ParticipantMetadataChanged(this, participant, prevMetadata), coroutineScope)
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
    override fun onTrackPublished(publication: RemoteTrackPublication, participant: RemoteParticipant) {
        listener?.onTrackPublished(publication,  participant, this)
        eventBus.postEvent(RoomEvent.TrackPublished(this, publication, participant), coroutineScope)
    }

    /**
     * @suppress
     */
    override fun onTrackUnpublished(publication: RemoteTrackPublication, participant: RemoteParticipant) {
        listener?.onTrackUnpublished(publication,  participant, this)
        eventBus.postEvent(RoomEvent.TrackUnpublished(this, publication, participant), coroutineScope)
    }

    /**
     * @suppress
     */
    override fun onTrackPublished(publication: LocalTrackPublication, participant: LocalParticipant) {
        listener?.onTrackPublished(publication,  participant, this)
        eventBus.postEvent(RoomEvent.TrackPublished(this, publication, participant), coroutineScope)
    }

    /**
     * @suppress
     */
    override fun onTrackUnpublished(publication: LocalTrackPublication, participant: LocalParticipant) {
        listener?.onTrackUnpublished(publication,  participant, this)
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
    fun onFailedToConnect(room: Room, error: Exception) {}
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