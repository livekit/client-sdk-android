package io.livekit.android.room

import android.os.SystemClock
import com.google.protobuf.ByteString
import io.livekit.android.ConnectOptions
import io.livekit.android.RoomOptions
import io.livekit.android.dagger.InjectionNames
import io.livekit.android.room.participant.ParticipantTrackPermission
import io.livekit.android.room.track.TrackException
import io.livekit.android.room.util.MediaConstraintKeys
import io.livekit.android.room.util.createAnswer
import io.livekit.android.room.util.setLocalDescription
import io.livekit.android.util.CloseableCoroutineScope
import io.livekit.android.util.Either
import io.livekit.android.util.LKLog
import io.livekit.android.webrtc.isConnected
import io.livekit.android.webrtc.isDisconnected
import io.livekit.android.webrtc.toProtoSessionDescription
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import livekit.LivekitModels
import livekit.LivekitRtc
import org.webrtc.*
import java.net.ConnectException
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * @suppress
 */
@Singleton
class RTCEngine
@Inject
internal constructor(
    val client: SignalClient,
    private val pctFactory: PeerConnectionTransport.Factory,
    @Named(InjectionNames.DISPATCHER_IO)
    private val ioDispatcher: CoroutineDispatcher,
) : SignalClient.Listener, DataChannel.Observer {
    internal var listener: Listener? = null

    /**
     * Reflects the combined connection state of SignalClient and primary PeerConnection.
     */
    internal var connectionState: ConnectionState = ConnectionState.DISCONNECTED
        set(value) {
            val oldVal = field
            field = value
            if (value == oldVal) {
                return
            }
            when (value) {
                ConnectionState.CONNECTED -> {
                    if (oldVal == ConnectionState.DISCONNECTED) {
                        LKLog.d { "primary ICE connected" }
                        listener?.onEngineConnected()
                    } else if (oldVal == ConnectionState.RECONNECTING) {
                        LKLog.d { "primary ICE reconnected" }
                        listener?.onEngineReconnected()
                    }
                }
                ConnectionState.DISCONNECTED -> {
                    LKLog.d { "primary ICE disconnected" }
                    if (oldVal == ConnectionState.CONNECTED) {
                        reconnect()
                    }
                }
                else -> {
                }
            }
        }

    private var reconnectingJob: Job? = null
    private val reconnectingLock = Mutex()
    private var fullReconnectOnNext = false

    private val pendingTrackResolvers: MutableMap<String, Continuation<LivekitModels.TrackInfo>> =
        mutableMapOf()
    private var sessionUrl: String? = null
    private var sessionToken: String? = null
    private var connectOptions: ConnectOptions? = null
    private var lastRoomOptions: RoomOptions? = null

    private val publisherObserver = PublisherTransportObserver(this, client)
    private val subscriberObserver = SubscriberTransportObserver(this, client)

    private var _publisher: PeerConnectionTransport? = null
    internal val publisher: PeerConnectionTransport
        get() {
            return _publisher
                ?: throw UninitializedPropertyAccessException("publisher has not been initialized yet.")
        }
    private var _subscriber: PeerConnectionTransport? = null
    internal val subscriber: PeerConnectionTransport
        get() {
            return _subscriber
                ?: throw UninitializedPropertyAccessException("subscriber has not been initialized yet.")
        }

    private var reliableDataChannel: DataChannel? = null
    private var reliableDataChannelSub: DataChannel? = null
    private var lossyDataChannel: DataChannel? = null
    private var lossyDataChannelSub: DataChannel? = null

    private var isSubscriberPrimary = false
    private var isClosed = true

    private var hasPublished = false

    private var coroutineScope = CloseableCoroutineScope(SupervisorJob() + ioDispatcher)

    init {
        client.listener = this
    }

    suspend fun join(
        url: String,
        token: String,
        options: ConnectOptions,
        roomOptions: RoomOptions
    ): LivekitRtc.JoinResponse {
        coroutineScope.close()
        coroutineScope = CloseableCoroutineScope(SupervisorJob() + ioDispatcher)
        sessionUrl = url
        sessionToken = token
        connectOptions = options
        lastRoomOptions = roomOptions
        return joinImpl(url, token, options, roomOptions)
    }

    suspend fun joinImpl(
        url: String,
        token: String,
        options: ConnectOptions,
        roomOptions: RoomOptions
    ): LivekitRtc.JoinResponse {
        val joinResponse = client.join(url, token, options, roomOptions)
        listener?.onJoinResponse(joinResponse)
        isClosed = false
        listener?.onSignalConnected(false)

        isSubscriberPrimary = joinResponse.subscriberPrimary

        configure(joinResponse, options)

        // create offer
        if (!this.isSubscriberPrimary) {
            negotiate()
        }
        client.onReadyForResponses()
        return joinResponse
    }

    private fun configure(joinResponse: LivekitRtc.JoinResponse, connectOptions: ConnectOptions?) {
        if (_publisher != null && _subscriber != null) {
            // already configured
            return
        }

        // update ICE servers before creating PeerConnection
        val iceServers = if (connectOptions?.iceServers != null) {
            connectOptions.iceServers
        } else {
            val servers = mutableListOf<PeerConnection.IceServer>()
            for (serverInfo in joinResponse.iceServersList) {
                val username = serverInfo.username ?: ""
                val credential = serverInfo.credential ?: ""
                servers.add(
                    PeerConnection.IceServer
                        .builder(serverInfo.urlsList)
                        .setUsername(username)
                        .setPassword(credential)
                        .createIceServer()
                )
            }

            if (servers.isEmpty()) {
                servers.addAll(SignalClient.DEFAULT_ICE_SERVERS)
            }
            servers
        }

        // Setup peer connections
        val rtcConfig = connectOptions?.rtcConfig?.apply {
            val mergedServers = this.iceServers.toMutableList()
            iceServers.forEach { server ->
                if (!mergedServers.contains(server)) {
                    mergedServers.add(server)
                }
            }
        }
            ?: PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy =
                    PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }

        _publisher?.close()
        _publisher = pctFactory.create(
            rtcConfig,
            publisherObserver,
            publisherObserver,
        )
        _subscriber?.close()
        _subscriber = pctFactory.create(
            rtcConfig,
            subscriberObserver,
            null,
        )

        val connectionStateListener: (PeerConnection.PeerConnectionState) -> Unit = { newState ->
            LKLog.v { "onIceConnection new state: $newState" }
            if (newState.isConnected()) {
                connectionState = ConnectionState.CONNECTED
            } else if (newState.isDisconnected()) {
                connectionState = ConnectionState.DISCONNECTED
            }
        }

        if (joinResponse.subscriberPrimary) {
            // in subscriber primary mode, server side opens sub data channels.
            subscriberObserver.dataChannelListener = onDataChannel@{ dataChannel: DataChannel ->
                when (dataChannel.label()) {
                    RELIABLE_DATA_CHANNEL_LABEL -> reliableDataChannelSub = dataChannel
                    LOSSY_DATA_CHANNEL_LABEL -> lossyDataChannelSub = dataChannel
                    else -> return@onDataChannel
                }
                dataChannel.registerObserver(this)
            }

            subscriberObserver.connectionChangeListener = connectionStateListener
            // Also reconnect on publisher disconnect
            publisherObserver.connectionChangeListener = { newState ->
                if (newState.isDisconnected()) {
                    reconnect()
                }
            }
        } else {
            publisherObserver.connectionChangeListener = connectionStateListener
        }

        // data channels
        val reliableInit = DataChannel.Init()
        reliableInit.ordered = true
        reliableDataChannel = publisher.peerConnection.createDataChannel(
            RELIABLE_DATA_CHANNEL_LABEL,
            reliableInit
        )
        reliableDataChannel!!.registerObserver(this)
        val lossyInit = DataChannel.Init()
        lossyInit.ordered = true
        lossyInit.maxRetransmits = 0
        lossyDataChannel = publisher.peerConnection.createDataChannel(
            LOSSY_DATA_CHANNEL_LABEL,
            lossyInit
        )
        lossyDataChannel!!.registerObserver(this)
    }

    /**
     * @param builder an optional builder to include other parameters related to the track
     */
    suspend fun addTrack(
        cid: String,
        name: String,
        kind: LivekitModels.TrackType,
        builder: LivekitRtc.AddTrackRequest.Builder = LivekitRtc.AddTrackRequest.newBuilder()
    ): LivekitModels.TrackInfo {
        if (pendingTrackResolvers[cid] != null) {
            throw TrackException.DuplicateTrackException("Track with same ID $cid has already been published!")
        }

        // Suspend until signal client receives message confirming track publication.
        return suspendCoroutine { cont ->
            pendingTrackResolvers[cid] = cont
            client.sendAddTrack(cid, name, kind, builder)
        }
    }

    fun updateSubscriptionPermissions(
        allParticipants: Boolean,
        participantTrackPermissions: List<ParticipantTrackPermission>
    ) {
        client.sendUpdateSubscriptionPermissions(allParticipants, participantTrackPermissions)
    }

    fun updateMuteStatus(sid: String, muted: Boolean) {
        client.sendMuteTrack(sid, muted)
    }

    fun close() {
        if (isClosed) {
            return
        }
        isClosed = true
        hasPublished = false
        sessionUrl = null
        sessionToken = null
        connectOptions = null
        lastRoomOptions = null
        reconnectingJob?.cancel()
        reconnectingJob = null
        coroutineScope.close()
        closeResources()
    }

    private fun closeResources() {
        connectionState = ConnectionState.DISCONNECTED
        _publisher?.close()
        _publisher = null
        _subscriber?.close()
        _subscriber = null
        reliableDataChannel?.close()
        reliableDataChannel = null
        reliableDataChannelSub?.close()
        reliableDataChannelSub = null
        lossyDataChannel?.close()
        lossyDataChannel = null
        lossyDataChannelSub?.close()
        lossyDataChannelSub = null
        isSubscriberPrimary = false
        client.close()
    }

    /**
     * reconnect Signal and PeerConnections
     */
    internal fun reconnect() {
        val didLock = reconnectingLock.tryLock()
        if (!didLock) {
            return
        }
        if (this.isClosed) {
            return
        }
        val url = sessionUrl
        val token = sessionToken
        if (url == null || token == null) {
            LKLog.w { "couldn't reconnect, no url or no token" }
            return
        }
        val forceFullReconnect = fullReconnectOnNext
        fullReconnectOnNext = false
        val job = coroutineScope.launch {

            connectionState = ConnectionState.RECONNECTING
            listener?.onEngineReconnecting()

            val reconnectStartTime = SystemClock.elapsedRealtime()
            for (retries in 0 until MAX_RECONNECT_RETRIES) {
                var startDelay = retries.toLong() * retries * 500
                if (startDelay > 5000) {
                    startDelay = 5000
                }

                LKLog.i { "Reconnecting to signal, attempt ${retries + 1}" }
                delay(startDelay)

                // full reconnect after first try.
                val isFullReconnect = retries != 0 || forceFullReconnect

                if (isFullReconnect) {
                    try {
                        closeResources()
                        listener?.onFullReconnecting()
                        joinImpl(url, token, connectOptions ?: ConnectOptions(), lastRoomOptions ?: RoomOptions())
                    } catch (e: Exception) {
                        LKLog.w(e) { "Error during reconnection." }
                        // reconnect failed, retry.
                        continue
                    }
                } else {
                    subscriber.prepareForIceRestart()
                    try {
                        client.reconnect(url, token)
                        // no join response for regular reconnects
                        client.onReadyForResponses()
                    } catch (e: Exception) {
                        LKLog.w(e) { "Error during reconnection." }
                        // ws reconnect failed, retry.
                        continue
                    }

                    LKLog.v { "ws reconnected, restarting ICE" }
                    listener?.onSignalConnected(!isFullReconnect)

                    // trigger publisher reconnect
                    // only restart publisher if it's needed
                    if (hasPublished) {
                        negotiate()
                    }
                }
                // wait until ICE connected
                val endTime = SystemClock.elapsedRealtime() + MAX_ICE_CONNECT_TIMEOUT_MS
                if (hasPublished) {
                    while (SystemClock.elapsedRealtime() < endTime) {
                        if (publisher.peerConnection.connectionState().isConnected()) {
                            LKLog.v { "publisher reconnected to ICE" }
                            break
                        }
                        delay(100)
                    }
                }

                while (SystemClock.elapsedRealtime() < endTime) {
                    if (connectionState == ConnectionState.CONNECTED) {
                        LKLog.v { "reconnected to ICE" }
                        break
                    }
                    delay(100)
                }

                if (connectionState == ConnectionState.CONNECTED &&
                    (!hasPublished || publisher.peerConnection.connectionState().isConnected())
                ) {
                    client.onPCConnected()
                    listener?.onPostReconnect(isFullReconnect)
                    return@launch
                }

                val curReconnectTime = SystemClock.elapsedRealtime() - reconnectStartTime
                if (curReconnectTime > MAX_RECONNECT_TIMEOUT) {
                    break
                }
            }

            close()
            listener?.onEngineDisconnected("failed reconnecting.")
        }

        reconnectingJob = job
        job.invokeOnCompletion {
            if (reconnectingJob == job) {
                reconnectingJob = null
            }
            reconnectingLock.unlock()
        }
    }

    internal fun negotiate() {
        if (!client.isConnected) {
            return
        }

        hasPublished = true

        coroutineScope.launch {
            publisher.negotiate(getPublisherOfferConstraints())
        }
    }

    internal suspend fun sendData(dataPacket: LivekitModels.DataPacket) {
        ensurePublisherConnected(dataPacket.kind)

        val buf = DataChannel.Buffer(
            ByteBuffer.wrap(dataPacket.toByteArray()),
            true,
        )

        val channel = dataChannelForKind(dataPacket.kind)
            ?: throw TrackException.PublishException("channel not established for ${dataPacket.kind.name}")

        channel.send(buf)
    }

    private suspend fun ensurePublisherConnected(kind: LivekitModels.DataPacket.Kind) {
        if (!isSubscriberPrimary) {
            return
        }

        if (_publisher == null) {
            throw RoomException.ConnectException("Publisher isn't setup yet! Is room not connected?!")
        }

        if (!publisher.peerConnection.isConnected() &&
            publisher.peerConnection.iceConnectionState() != PeerConnection.IceConnectionState.CHECKING
        ) {
            // start negotiation
            this.negotiate()
        }


        val targetChannel = dataChannelForKind(kind) ?: throw IllegalArgumentException("Unknown data packet kind!")
        if (targetChannel.state() == DataChannel.State.OPEN) {
            return
        }

        // wait until publisher ICE connected
        val endTime = SystemClock.elapsedRealtime() + MAX_ICE_CONNECT_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < endTime) {
            if (this.publisher.peerConnection.isConnected() && targetChannel.state() == DataChannel.State.OPEN) {
                return
            }
            delay(50)
        }

        throw ConnectException("could not establish publisher connection")
    }

    private fun dataChannelForKind(kind: LivekitModels.DataPacket.Kind) =
        when (kind) {
            LivekitModels.DataPacket.Kind.RELIABLE -> reliableDataChannel
            LivekitModels.DataPacket.Kind.LOSSY -> lossyDataChannel
            else -> null
        }

    private fun getPublisherOfferConstraints(): MediaConstraints {
        return MediaConstraints().apply {
            with(mandatory) {
                add(
                    MediaConstraints.KeyValuePair(
                        MediaConstraintKeys.OFFER_TO_RECV_AUDIO,
                        MediaConstraintKeys.FALSE
                    )
                )
                add(
                    MediaConstraints.KeyValuePair(
                        MediaConstraintKeys.OFFER_TO_RECV_VIDEO,
                        MediaConstraintKeys.FALSE
                    )
                )
                if (connectionState == ConnectionState.RECONNECTING) {
                    add(
                        MediaConstraints.KeyValuePair(
                            MediaConstraintKeys.ICE_RESTART,
                            MediaConstraintKeys.TRUE
                        )
                    )
                }
            }
        }
    }

    internal interface Listener {
        fun onEngineConnected()
        fun onEngineReconnected()
        fun onEngineReconnecting()
        fun onEngineDisconnected(reason: String)
        fun onFailToConnect(error: Throwable)
        fun onJoinResponse(response: LivekitRtc.JoinResponse)
        fun onAddTrack(track: MediaStreamTrack, streams: Array<out MediaStream>)
        fun onUpdateParticipants(updates: List<LivekitModels.ParticipantInfo>)
        fun onActiveSpeakersUpdate(speakers: List<LivekitModels.SpeakerInfo>)
        fun onRemoteMuteChanged(trackSid: String, muted: Boolean)
        fun onRoomUpdate(update: LivekitModels.Room)
        fun onConnectionQuality(updates: List<LivekitRtc.ConnectionQualityInfo>)
        fun onSpeakersChanged(speakers: List<LivekitModels.SpeakerInfo>)
        fun onUserPacket(packet: LivekitModels.UserPacket, kind: LivekitModels.DataPacket.Kind)
        fun onStreamStateUpdate(streamStates: List<LivekitRtc.StreamStateInfo>)
        fun onSubscribedQualityUpdate(subscribedQualityUpdate: LivekitRtc.SubscribedQualityUpdate)
        fun onSubscriptionPermissionUpdate(subscriptionPermissionUpdate: LivekitRtc.SubscriptionPermissionUpdate)
        fun onSignalConnected(isResume: Boolean)
        fun onFullReconnecting()
        suspend fun onPostReconnect(isFullReconnect: Boolean)
        fun onLocalTrackUnpublished(trackUnpublished: LivekitRtc.TrackUnpublishedResponse)
    }

    companion object {
        private const val RELIABLE_DATA_CHANNEL_LABEL = "_reliable"
        private const val LOSSY_DATA_CHANNEL_LABEL = "_lossy"
        internal const val MAX_DATA_PACKET_SIZE = 15000
        private const val MAX_RECONNECT_RETRIES = 10
        private const val MAX_RECONNECT_TIMEOUT = 60 * 1000
        private const val MAX_ICE_CONNECT_TIMEOUT_MS = 20000

        internal val CONN_CONSTRAINTS = MediaConstraints().apply {
            with(optional) {
                add(MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"))
            }
        }
    }

    //---------------------------------- SignalClient.Listener --------------------------------------//

    override fun onAnswer(sessionDescription: SessionDescription) {
        LKLog.v { "received server answer: ${sessionDescription.type}, ${publisher.peerConnection.signalingState()}" }
        coroutineScope.launch {
            LKLog.i { sessionDescription.toString() }
            when (val outcome = publisher.setRemoteDescription(sessionDescription)) {
                is Either.Left -> {
                    // do nothing.
                }
                is Either.Right -> {
                    LKLog.e { "error setting remote description for answer: ${outcome.value} " }
                }
            }
        }
    }

    override fun onOffer(sessionDescription: SessionDescription) {
        LKLog.v { "received server offer: ${sessionDescription.type}, ${subscriber.peerConnection.signalingState()}" }
        coroutineScope.launch {
            run<Unit> {
                when (val outcome =
                    subscriber.setRemoteDescription(sessionDescription)) {
                    is Either.Right -> {
                        LKLog.e { "error setting remote description for answer: ${outcome.value} " }
                        return@launch
                    }
                }
            }

            val answer = run {
                when (val outcome = subscriber.peerConnection.createAnswer(MediaConstraints())) {
                    is Either.Left -> outcome.value
                    is Either.Right -> {
                        LKLog.e { "error creating answer: ${outcome.value}" }
                        return@launch
                    }
                }
            }

            run<Unit> {
                when (val outcome = subscriber.peerConnection.setLocalDescription(answer)) {
                    is Either.Right -> {
                        LKLog.e { "error setting local description for answer: ${outcome.value}" }
                        return@launch
                    }
                }
            }

            client.sendAnswer(answer)
        }
    }

    override fun onTrickle(candidate: IceCandidate, target: LivekitRtc.SignalTarget) {
        LKLog.v { "received ice candidate from peer: $candidate, $target" }
        when (target) {
            LivekitRtc.SignalTarget.PUBLISHER -> publisher.addIceCandidate(candidate)
            LivekitRtc.SignalTarget.SUBSCRIBER -> subscriber.addIceCandidate(candidate)
            else -> LKLog.i { "unknown ice candidate target?" }
        }
    }

    override fun onLocalTrackPublished(response: LivekitRtc.TrackPublishedResponse) {
        val cid = response.cid ?: run {
            LKLog.e { "local track published with null cid?" }
            return
        }

        val track = response.track
        if (track == null) {
            LKLog.d { "local track published with null track info?" }
        }

        LKLog.v { "local track published $cid" }
        val cont = pendingTrackResolvers.remove(cid)
        if (cont == null) {
            LKLog.d { "missing track resolver for: $cid" }
            return
        }
        cont.resume(response.track)
    }

    override fun onParticipantUpdate(updates: List<LivekitModels.ParticipantInfo>) {
        listener?.onUpdateParticipants(updates)
    }

    override fun onSpeakersChanged(speakers: List<LivekitModels.SpeakerInfo>) {
        listener?.onSpeakersChanged(speakers)
    }

    override fun onClose(reason: String, code: Int) {
        LKLog.i { "received close event: $reason, code: $code" }
        reconnect()
    }

    override fun onRemoteMuteChanged(trackSid: String, muted: Boolean) {
        listener?.onRemoteMuteChanged(trackSid, muted)
    }

    override fun onRoomUpdate(update: LivekitModels.Room) {
        listener?.onRoomUpdate(update)
    }

    override fun onConnectionQuality(updates: List<LivekitRtc.ConnectionQualityInfo>) {
        listener?.onConnectionQuality(updates)
    }

    override fun onLeave(leave: LivekitRtc.LeaveRequest) {
        if (leave.canReconnect) {
            // reconnect will be triggered on close.
            fullReconnectOnNext = true
        } else {
            close()
            listener?.onEngineDisconnected("server leave")
        }
    }

    // Signal error
    override fun onError(error: Throwable) {
        if (connectionState == ConnectionState.CONNECTING) {
            listener?.onFailToConnect(error)
        }
    }

    override fun onStreamStateUpdate(streamStates: List<LivekitRtc.StreamStateInfo>) {
        listener?.onStreamStateUpdate(streamStates)
    }

    override fun onSubscribedQualityUpdate(subscribedQualityUpdate: LivekitRtc.SubscribedQualityUpdate) {
        listener?.onSubscribedQualityUpdate(subscribedQualityUpdate)
    }

    override fun onSubscriptionPermissionUpdate(subscriptionPermissionUpdate: LivekitRtc.SubscriptionPermissionUpdate) {
        listener?.onSubscriptionPermissionUpdate(subscriptionPermissionUpdate)
    }

    override fun onRefreshToken(token: String) {
        sessionToken = token
    }

    override fun onLocalTrackUnpublished(trackUnpublished: LivekitRtc.TrackUnpublishedResponse) {
        listener?.onLocalTrackUnpublished(trackUnpublished)
    }

    //--------------------------------- DataChannel.Observer ------------------------------------//

    override fun onBufferedAmountChange(previousAmount: Long) {
    }

    override fun onStateChange() {
    }

    override fun onMessage(buffer: DataChannel.Buffer?) {
        if (buffer == null) {
            return
        }
        val dp = LivekitModels.DataPacket.parseFrom(ByteString.copyFrom(buffer.data))
        when (dp.valueCase) {
            LivekitModels.DataPacket.ValueCase.SPEAKER -> {
                listener?.onActiveSpeakersUpdate(dp.speaker.speakersList)
            }
            LivekitModels.DataPacket.ValueCase.USER -> {
                listener?.onUserPacket(dp.user, dp.kind)
            }
            LivekitModels.DataPacket.ValueCase.VALUE_NOT_SET,
            null -> {
                LKLog.v { "invalid value for data packet" }
            }
        }
    }

    fun sendSyncState(
        subscription: LivekitRtc.UpdateSubscription,
        publishedTracks: List<LivekitRtc.TrackPublishedResponse>
    ) {
        val answer = subscriber.peerConnection.localDescription?.toProtoSessionDescription()

        val dataChannelInfos = LivekitModels.DataPacket.Kind.values()
            .toList()
            .mapNotNull { kind -> dataChannelForKind(kind) }
            .map { dataChannel ->
                LivekitRtc.DataChannelInfo.newBuilder()
                    .setId(dataChannel.id())
                    .setLabel(dataChannel.label())
                    .build()
            }

        val syncState = with(LivekitRtc.SyncState.newBuilder()) {
            if (answer != null) {
                setAnswer(answer)
            }
            setSubscription(subscription)
            addAllPublishTracks(publishedTracks)
            addAllDataChannels(dataChannelInfos)
            build()
        }

        client.sendSyncState(syncState)
    }
}