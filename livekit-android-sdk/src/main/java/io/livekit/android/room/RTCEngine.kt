package io.livekit.android.room

import android.os.SystemClock
import io.livekit.android.ConnectOptions
import io.livekit.android.dagger.InjectionNames
import io.livekit.android.room.track.DataPublishReliability
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.TrackException
import io.livekit.android.room.track.TrackPublication
import io.livekit.android.room.util.*
import io.livekit.android.util.CloseableCoroutineScope
import io.livekit.android.util.Either
import io.livekit.android.util.LKLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import livekit.LivekitModels
import livekit.LivekitRtc
import org.webrtc.*
import java.net.ConnectException
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
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
    @Named(InjectionNames.DISPATCHER_IO) ioDispatcher: CoroutineDispatcher,
) : SignalClient.Listener, DataChannel.Observer {
    internal var listener: Listener? = null
    internal var iceState: IceState = IceState.DISCONNECTED
        set(value) {
            val oldVal = field
            field = value
            if (value == oldVal) {
                return
            }
            when (value) {
                IceState.CONNECTED -> {
                    if (oldVal == IceState.DISCONNECTED) {
                        LKLog.d { "publisher ICE connected" }
                        listener?.onIceConnected()
                    } else if (oldVal == IceState.RECONNECTING) {
                        LKLog.d { "publisher ICE reconnected" }
                        listener?.onIceReconnected()
                    }
                }
                IceState.DISCONNECTED -> {
                    LKLog.d { "publisher ICE disconnected" }
                    listener?.onDisconnect("Peer connection disconnected")
                }
                else -> {
                }
            }
        }
    private var wsRetries: Int = 0
    private val pendingTrackResolvers: MutableMap<String, Continuation<LivekitModels.TrackInfo>> =
        mutableMapOf()
    private var sessionUrl: String? = null
    private var sessionToken: String? = null

    private val publisherObserver = PublisherTransportObserver(this, client)
    private val subscriberObserver = SubscriberTransportObserver(this, client)
    internal lateinit var publisher: PeerConnectionTransport
    private lateinit var subscriber: PeerConnectionTransport
    private var reliableDataChannel: DataChannel? = null
    private var reliableDataChannelSub: DataChannel? = null
    private var lossyDataChannel: DataChannel? = null
    private var lossyDataChannelSub: DataChannel? = null

    private var isSubscriberPrimary = false
    private var isClosed = true

    private var hasPublished = false

    private val coroutineScope = CloseableCoroutineScope(SupervisorJob() + ioDispatcher)

    init {
        client.listener = this
    }

    suspend fun join(url: String, token: String, options: ConnectOptions?): LivekitRtc.JoinResponse {
        sessionUrl = url
        sessionToken = token
        val joinResponse = client.join(url, token, options)
        isClosed = false

        isSubscriberPrimary = joinResponse.subscriberPrimary

        if (!this::publisher.isInitialized) {
            configure(joinResponse)
        }
        // create offer
        if (!this.isSubscriberPrimary) {
            negotiate()
        }
        client.onReady()
        return joinResponse
    }

    private fun configure(joinResponse: LivekitRtc.JoinResponse) {
        if (this::publisher.isInitialized || this::subscriber.isInitialized) {
            // already configured
            return
        }

        // update ICE servers before creating PeerConnection
        val iceServers = mutableListOf<PeerConnection.IceServer>()
        for (serverInfo in joinResponse.iceServersList) {
            val username = serverInfo.username ?: ""
            val credential = serverInfo.credential ?: ""
            iceServers.add(
                PeerConnection.IceServer
                    .builder(serverInfo.urlsList)
                    .setUsername(username)
                    .setPassword(credential)
                    .createIceServer()
            )
        }

        if (iceServers.isEmpty()) {
            iceServers.addAll(SignalClient.DEFAULT_ICE_SERVERS)
        }
        joinResponse.iceServersList.forEach {
            LKLog.v { "username = \"${it.username}\"" }
            LKLog.v { "credential = \"${it.credential}\"" }
            LKLog.v { "urls: " }
            it.urlsList.forEach {
                LKLog.v { "   $it" }
            }
        }

        // Setup peer connections
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            enableDtlsSrtp = true
        }

        publisher = pctFactory.create(
            rtcConfig,
            publisherObserver,
            publisherObserver,
        )
        subscriber = pctFactory.create(
            rtcConfig,
            subscriberObserver,
            null,
        )

        val iceConnectionStateListener: (PeerConnection.IceConnectionState?) -> Unit = { newState ->
            val state =
                newState ?: throw NullPointerException("unexpected null new state, what do?")
            LKLog.v { "onIceConnection new state: $newState" }
            if (state == PeerConnection.IceConnectionState.CONNECTED) {
                iceState = IceState.CONNECTED
            } else if (state == PeerConnection.IceConnectionState.FAILED) {
                // when we publish tracks, some WebRTC versions will send out disconnected events periodically
                iceState = IceState.DISCONNECTED
                listener?.onDisconnect("Peer connection disconnected")
            }
        }

        if (joinResponse.subscriberPrimary) {
            // in subscriber primary mode, server side opens sub data channels.
            publisherObserver.dataChannelListener = onDataChannel@{ dataChannel: DataChannel? ->
                if (dataChannel == null) {
                    return@onDataChannel
                }
                when (dataChannel.label()) {
                    RELIABLE_DATA_CHANNEL_LABEL -> reliableDataChannelSub = dataChannel
                    LOSSY_DATA_CHANNEL_LABEL -> lossyDataChannelSub = dataChannel
                    else -> return@onDataChannel
                }
                dataChannel.registerObserver(this)
            }
            publisherObserver.iceConnectionChangeListener = iceConnectionStateListener
        } else {
            subscriberObserver.iceConnectionChangeListener = iceConnectionStateListener
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

        return suspendCoroutine { cont ->
            pendingTrackResolvers[cid] = cont
            client.sendAddTrack(cid, name, kind, builder)
        }
    }

    fun updateMuteStatus(sid: String, muted: Boolean) {
        client.sendMuteTrack(sid, muted)
    }

    fun close() {
        coroutineScope.close()
        publisher.close()
        subscriber.close()
        client.close()
    }

    /**
     * reconnect Signal and PeerConnections
     */
    internal fun reconnect() {
        val url = sessionUrl
        val token = sessionToken
        if (url == null || token == null) {
            LKLog.w { "couldn't reconnect, no url or no token" }
            return
        }
        if (iceState == IceState.DISCONNECTED || wsRetries >= MAX_SIGNAL_RETRIES) {
            LKLog.w { "could not connect to signal after max attempts, giving up" }
            close()
            listener?.onDisconnect("could not reconnect after limit")
            return
        }

        var startDelay = wsRetries.toLong() * wsRetries * 500
        if (startDelay > 5000) {
            startDelay = 5000
        }
        coroutineScope.launch {
            delay(startDelay)
            if (iceState == IceState.DISCONNECTED) {
                LKLog.e { "Ice is disconnected" }
                return@launch
            }

            client.reconnect(url, token)

            LKLog.v { "reconnected, restarting ICE" }
            wsRetries = 0

            // trigger publisher reconnect
            subscriber.prepareForIceRestart()
            // only restart publisher if it's needed
            if (hasPublished) {
                publisher.negotiate(
                    getPublisherOfferConstraints().apply {
                        with(mandatory){
                            add(
                                MediaConstraints.KeyValuePair(
                                    MediaConstraintKeys.ICE_RESTART,
                                    MediaConstraintKeys.TRUE
                                )
                            )
                        }
                    }
                )
            }

        }
    }

    internal fun negotiate() {
        if (!client.isConnected) {
            return
        }
        coroutineScope.launch {
            publisher.negotiate(getPublisherOfferConstraints())
        }
    }

    internal suspend fun sendData(dataPacket: LivekitModels.DataPacket) {
        ensurePublisherConnected()

        val buf = DataChannel.Buffer(
            ByteBuffer.wrap(dataPacket.toByteArray()),
            true,
        )

        val channel = when (dataPacket.kind) {
            LivekitModels.DataPacket.Kind.RELIABLE -> reliableDataChannel
            LivekitModels.DataPacket.Kind.LOSSY -> lossyDataChannel
            else -> null
        } ?: throw TrackException.PublishException("channel not established for ${dataPacket.kind.name}")

        channel.send(buf)
    }

    private suspend fun ensurePublisherConnected(){
        if (!isSubscriberPrimary) {
            return
        }

        if (this.publisher.peerConnection.iceConnectionState() == PeerConnection.IceConnectionState.CONNECTED) {
            return
        }

        // start negotiation
        this.negotiate()

        // wait until publisher ICE connected
        val endTime = SystemClock.elapsedRealtime() + MAX_ICE_CONNECT_TIMEOUT_MS;
        while (SystemClock.elapsedRealtime() < endTime) {
            if (this.publisher.peerConnection.iceConnectionState() == PeerConnection.IceConnectionState.CONNECTED) {
                return
            }
            delay(50)
        }

        throw ConnectException("could not establish publisher connection")
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
                if (iceState == IceState.RECONNECTING) {
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
        fun onIceConnected()
        fun onIceReconnected()
        fun onAddTrack(track: MediaStreamTrack, streams: Array<out MediaStream>)
        fun onUpdateParticipants(updates: List<LivekitModels.ParticipantInfo>)
        fun onActiveSpeakersUpdate(speakers: List<LivekitModels.SpeakerInfo>)
        fun onRemoteMuteChanged(trackSid: String, muted: Boolean)
        fun onRoomUpdate(update: LivekitModels.Room)
        fun onSpeakersChanged(speakers: List<LivekitModels.SpeakerInfo>)
        fun onDisconnect(reason: String)
        fun onFailToConnect(error: Exception)
        fun onUserPacket(packet: LivekitModels.UserPacket, kind: LivekitModels.DataPacket.Kind)
    }

    companion object {
        private const val RELIABLE_DATA_CHANNEL_LABEL = "_reliable"
        private const val LOSSY_DATA_CHANNEL_LABEL = "_lossy"
        internal const val MAX_DATA_PACKET_SIZE = 15000
        private const val MAX_SIGNAL_RETRIES = 5
        private const val MAX_ICE_CONNECT_TIMEOUT_MS = 5000

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
                    // when reconnecting, ICE might not have disconnected and won't trigger
                    // our connected callback, so we'll take a shortcut and set it to active
                    if (iceState == IceState.RECONNECTING) {
                        iceState = IceState.CONNECTED
                    }
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
        // TODO: reconnect logic
        LKLog.i { "received close event: $reason, code: $code" }
        listener?.onDisconnect(reason)
    }

    override fun onRemoteMuteChanged(trackSid: String, muted: Boolean) {
        listener?.onRemoteMuteChanged(trackSid, muted)
    }

    override fun onRoomUpdate(update: LivekitModels.Room) {
        listener?.onRoomUpdate(update)
    }

    override fun onLeave() {
        close()
        listener?.onDisconnect("")
    }

    override fun onError(error: Exception) {
        listener?.onFailToConnect(error)
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
        val dp = LivekitModels.DataPacket.parseFrom(buffer.data)
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
}

 internal enum class IceState {
    DISCONNECTED,
    RECONNECTING,
    CONNECTED,
}