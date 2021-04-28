package io.livekit.android.room

import com.github.ajalt.timberkt.Timber
import io.livekit.android.dagger.InjectionNames
import io.livekit.android.room.track.TrackException
import io.livekit.android.room.util.*
import io.livekit.android.util.CloseableCoroutineScope
import io.livekit.android.util.Either
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import livekit.LivekitModels
import livekit.LivekitRtc
import org.webrtc.*
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
constructor(
    val client: RTCClient,
    private val pctFactory: PeerConnectionTransport.Factory,
    @Named(InjectionNames.DISPATCHER_IO) ioDispatcher: CoroutineDispatcher,
) : RTCClient.Listener, DataChannel.Observer {

    var listener: Listener? = null
    var rtcConnected: Boolean = false
    var iceConnected: Boolean = false
    private val pendingTrackResolvers: MutableMap<String, Continuation<LivekitModels.TrackInfo>> =
        mutableMapOf()

    private val publisherObserver = PublisherTransportObserver(this)
    private val subscriberObserver = SubscriberTransportObserver(this)
    internal lateinit var publisher: PeerConnectionTransport
    private lateinit var subscriber: PeerConnectionTransport
    internal var reliableDataChannel: DataChannel? = null
    internal var lossyDataChannel: DataChannel? = null

    private val coroutineScope = CloseableCoroutineScope(SupervisorJob() + ioDispatcher)
    init {
        client.listener = this
    }

    fun join(url: String, token: String) {
        client.join(url, token)
    }

    suspend fun addTrack(cid: String, name: String, kind: LivekitModels.TrackType): LivekitModels.TrackInfo {
        if (pendingTrackResolvers[cid] != null) {
            throw TrackException.DuplicateTrackException("Track with same ID $cid has already been published!")
        }

        return suspendCoroutine { cont ->
            pendingTrackResolvers[cid] = cont
            client.sendAddTrack(cid, name, kind)
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

    fun negotiate() {
        if (!client.isConnected) {
            return
        }
        coroutineScope.launch {
            val sdpOffer =
                when (val outcome = publisher.peerConnection.createOffer(OFFER_CONSTRAINTS)) {
                    is Either.Left -> outcome.value
                    is Either.Right -> {
                        Timber.d { "error creating offer: ${outcome.value}" }
                        return@launch
                    }
                }

            Timber.v { "sdp offer = $sdpOffer, description: ${sdpOffer.description}, type: ${sdpOffer.type}" }
            when (val outcome = publisher.peerConnection.setLocalDescription(sdpOffer)) {
                is Either.Right -> {
                    Timber.d { "error setting local description: ${outcome.value}" }
                    return@launch
                }
            }

            client.sendOffer(sdpOffer)
        }
    }

    private fun onRTCConnected() {
        Timber.v { "RTC Connected" }
        rtcConnected = true
    }

    interface Listener {
        fun onJoin(response: LivekitRtc.JoinResponse)
        fun onICEConnected()
        fun onAddTrack(track: MediaStreamTrack, streams: Array<out MediaStream>)
//        fun onPublishLocalTrack(cid: String, track: LivekitModels.TrackInfo)
        fun onAddDataChannel(channel: DataChannel)
        fun onUpdateParticipants(updates: List<LivekitModels.ParticipantInfo>)
        fun onUpdateSpeakers(speakers: List<LivekitRtc.SpeakerInfo>)
        fun onDisconnect(reason: String)
        fun onFailToConnect(error: Exception)
        fun onUserPacket(packet: LivekitRtc.UserPacket, kind: LivekitRtc.DataPacket.Kind)
    }

    companion object {
        private const val RELIABLE_DATA_CHANNEL_LABEL = "_reliable"
        private const val LOSSY_DATA_CHANNEL_LABEL = "_lossy"
        internal const val MAX_DATA_PACKET_SIZE = 15000

        private val OFFER_CONSTRAINTS = MediaConstraints().apply {
            with(mandatory) {
                add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
                add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            }
        }

        private val MEDIA_CONSTRAINTS = MediaConstraints()

        internal val CONN_CONSTRAINTS = MediaConstraints().apply {
            with(optional) {
                add(MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"))
            }
        }
    }

    //---------------------------------- RTCClient.Listener --------------------------------------//

    override fun onJoin(info: LivekitRtc.JoinResponse) {
        val iceServers = mutableListOf<PeerConnection.IceServer>()
        for(serverInfo in info.iceServersList){
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

        if(iceServers.isEmpty()){
            iceServers.addAll(RTCClient.DEFAULT_ICE_SERVERS)
        }
        info.iceServersList.forEach {
            Timber.e{ "username = \"${it.username}\""}
            Timber.e{ "credential = \"${it.credential}\""}
            Timber.e{ "urls: "}
            it.urlsList.forEach{
                Timber.e{"   $it"}
            }
        }

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        publisher = pctFactory.create(rtcConfig, publisherObserver)
        subscriber = pctFactory.create(rtcConfig, subscriberObserver)

        val reliableInit = DataChannel.Init()
        reliableInit.ordered = true
        reliableDataChannel = publisher.peerConnection.createDataChannel(
            RELIABLE_DATA_CHANNEL_LABEL,
            reliableInit
        )
        reliableDataChannel!!.registerObserver(this)
        val lossyInit = DataChannel.Init()
        lossyInit.ordered = true
        lossyInit.maxRetransmits = 1
        lossyDataChannel = publisher.peerConnection.createDataChannel(
            LOSSY_DATA_CHANNEL_LABEL,
            lossyInit
        )

        coroutineScope.launch {
            val sdpOffer =
                when (val outcome = publisher.peerConnection.createOffer(OFFER_CONSTRAINTS)) {
                    is Either.Left -> outcome.value
                    is Either.Right -> {
                        Timber.d { "error creating offer: ${outcome.value}" }
                        return@launch
                    }
                }

            when (val outcome = publisher.peerConnection.setLocalDescription(sdpOffer)) {
                is Either.Right -> {
                    Timber.d { "error setting local description: ${outcome.value}" }
                    return@launch
                }
            }

            client.sendOffer(sdpOffer)
        }
        listener?.onJoin(info)
    }

    override fun onAnswer(sessionDescription: SessionDescription) {
        Timber.v { "received server answer: ${sessionDescription.type}, ${publisher.peerConnection.signalingState()}" }
        coroutineScope.launch {
            Timber.i { sessionDescription.toString() }
            when (val outcome = publisher.setRemoteDescription(sessionDescription)) {
                is Either.Left -> {
                    if (!rtcConnected) {
                        onRTCConnected()
                    }
                }
                is Either.Right -> {
                    Timber.e { "error setting remote description for answer: ${outcome.value} " }
                }
            }
        }
    }

    override fun onOffer(sessionDescription: SessionDescription) {
        Timber.v { "received server offer: ${sessionDescription.type}, ${subscriber.peerConnection.signalingState()}" }
        coroutineScope.launch {
            run<Unit> {
                when (val outcome =
                    subscriber.setRemoteDescription(sessionDescription)) {
                    is Either.Right -> {
                        Timber.e { "error setting remote description for answer: ${outcome.value} " }
                        return@launch
                    }
                }
            }

            val answer = run {
                when (val outcome = subscriber.peerConnection.createAnswer(OFFER_CONSTRAINTS)) {
                    is Either.Left -> outcome.value
                    is Either.Right -> {
                        Timber.e { "error creating answer: ${outcome.value}" }
                        return@launch
                    }
                }
            }

            run<Unit> {
                when (val outcome = subscriber.peerConnection.setLocalDescription(answer)) {
                    is Either.Right -> {
                        Timber.e { "error setting local description for answer: ${outcome.value}" }
                        return@launch
                    }
                }
            }

            client.sendAnswer(answer)
        }
    }

    override fun onTrickle(candidate: IceCandidate, target: LivekitRtc.SignalTarget) {
        Timber.v { "received ice candidate from peer: $candidate, $target" }
        when (target) {
            LivekitRtc.SignalTarget.PUBLISHER -> publisher.addIceCandidate(candidate)
            LivekitRtc.SignalTarget.SUBSCRIBER -> subscriber.addIceCandidate(candidate)
            else -> Timber.i { "unknown ice candidate target?" }
        }
    }

    override fun onLocalTrackPublished(response: LivekitRtc.TrackPublishedResponse) {
        val signalCid = response.cid ?: run {
            Timber.e { "local track published with null cid?" }
            return
        }
        val cid = signalCid

        val track = response.track
        if (track == null) {
            Timber.d { "local track published with null track info?" }
        }

        Timber.v { "local track published $cid" }
        val cont = pendingTrackResolvers.remove(cid)
        if (cont == null) {
            Timber.d { "missing track resolver for: $cid" }
            return
        }
        cont.resume(response.track)
//        listener?.onPublishLocalTrack(cid, track)
    }

    override fun onParticipantUpdate(updates: List<LivekitModels.ParticipantInfo>) {
        listener?.onUpdateParticipants(updates)
    }

    override fun onActiveSpeakersChanged(speakers: List<LivekitRtc.SpeakerInfo>) {
        listener?.onUpdateSpeakers(speakers)
    }

    override fun onClose(reason: String, code: Int) {
        // TODO: reconnect logic
        Timber.i { "received close event: $reason, code: $code" }
        listener?.onDisconnect(reason)
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
        val dp = LivekitRtc.DataPacket.parseFrom(buffer.data)
        when (dp.valueCase) {
            LivekitRtc.DataPacket.ValueCase.SPEAKER -> {
                listener?.onUpdateSpeakers(dp.speaker.speakersList)
            }
            LivekitRtc.DataPacket.ValueCase.USER -> {
                listener?.onUserPacket(dp.user, dp.kind)
            }
            LivekitRtc.DataPacket.ValueCase.VALUE_NOT_SET,
            null -> {
                Timber.v { "invalid value for data packet" }
            }
        }
    }
}