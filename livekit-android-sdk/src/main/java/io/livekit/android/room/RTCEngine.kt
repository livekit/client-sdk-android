package io.livekit.android.room

import android.content.Context
import com.github.ajalt.timberkt.Timber
import io.livekit.android.dagger.InjectionNames
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.TrackException
import io.livekit.android.room.util.CoroutineSdpObserver
import io.livekit.android.util.CloseableCoroutineScope
import io.livekit.android.util.Either
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import livekit.Model
import livekit.Rtc
import org.webrtc.*
import javax.inject.Inject
import javax.inject.Named
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine


class RTCEngine
@Inject
constructor(
    private val appContext: Context,
    val client: RTCClient,
    pctFactory: PeerConnectionTransport.Factory,
    @Named(InjectionNames.DISPATCHER_IO) ioDispatcher: CoroutineDispatcher,
) : RTCClient.Listener {

    var listener: Listener? = null
    var rtcConnected: Boolean = false
    var joinResponse: Rtc.JoinResponse? = null
    var iceConnected: Boolean = false
        set(value) {
            field = value
            if (field) {
                // TODO get rid of this assertion
                listener?.onJoin(joinResponse!!)
                joinResponse = null
            }
        }
    val pendingCandidates = mutableListOf<IceCandidate>()
    private val pendingTrackResolvers: MutableMap<Track.Cid, Continuation<Model.TrackInfo>> =
        mutableMapOf()

    private val publisherObserver = PublisherTransportObserver(this)
    private val subscriberObserver = SubscriberTransportObserver(this)
    internal val publisher: PeerConnectionTransport
    private val subscriber: PeerConnectionTransport

    private var privateDataChannel: DataChannel

    private val coroutineScope = CloseableCoroutineScope(SupervisorJob() + ioDispatcher)
    init {
        val rtcConfig = PeerConnection.RTCConfiguration(RTCClient.DEFAULT_ICE_SERVERS).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        publisher = pctFactory.create(rtcConfig, publisherObserver)
        subscriber = pctFactory.create(rtcConfig, subscriberObserver)
        client.listener = this

        privateDataChannel = publisher.peerConnection.createDataChannel(
            PRIVATE_DATA_CHANNEL_LABEL,
            DataChannel.Init()
        )
    }

    fun join(url: String, token: String, isSecure: Boolean) {
        client.join(url, token, isSecure)
    }

    suspend fun addTrack(cid: Track.Cid, name: String, kind: Model.TrackType): Model.TrackInfo {
        if (pendingTrackResolvers[cid] != null) {
            throw TrackException.DuplicateTrackException("Track with same ID $cid has already been published!")
        }

        return suspendCoroutine { cont ->
            pendingTrackResolvers[cid] = cont
            client.sendAddTrack(cid, name, kind)
        }
    }

    fun updateMuteStatus(sid: Track.Sid, muted: Boolean) {
        client.sendMuteTrack(sid, muted)
    }

    fun close() {
        publisher.close()
        subscriber.close()
        client.close()
    }

    fun negotiate() {

        if (!client.isConnected) {
            return
        }
        coroutineScope.launch {
            val offerObserver = CoroutineSdpObserver()
            publisher.peerConnection.createOffer(offerObserver, OFFER_CONSTRAINTS)
            val sdpOffer = when (val outcome = offerObserver.awaitCreate()) {
                is Either.Left -> outcome.value
                is Either.Right -> {
                    Timber.d { "error creating offer: ${outcome.value}" }
                    return@launch
                }
            }

            val setObserver = CoroutineSdpObserver()
            publisher.peerConnection.setLocalDescription(setObserver, sdpOffer)
            when (val outcome = setObserver.awaitSet()) {
                is Either.Left -> client.sendOffer(sdpOffer)
                is Either.Right -> Timber.d { "error setting local description: ${outcome.value}" }
            }
        }
    }

    private fun onRTCConnected() {
        Timber.v { "RTC Connected" }
        rtcConnected = true
        pendingCandidates.forEach { candidate ->
            client.sendCandidate(candidate, Rtc.SignalTarget.PUBLISHER)
        }
        pendingCandidates.clear()
    }

    interface Listener {
        fun onJoin(response: Rtc.JoinResponse)
        fun onAddTrack(track: MediaStreamTrack, streams: Array<out MediaStream>)
        fun onPublishLocalTrack(cid: String, track: Model.TrackInfo)
        fun onAddDataChannel(channel: DataChannel)
        fun onUpdateParticipants(updates: List<Model.ParticipantInfo>)
        fun onUpdateSpeakers(speakers: List<Rtc.SpeakerInfo>)
        fun onDisconnect(reason: String)
        fun onFailToConnect(error: Exception)
    }

    companion object {
        private const val PRIVATE_DATA_CHANNEL_LABEL = "_private"

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

    override fun onJoin(info: Rtc.JoinResponse) {
        joinResponse = info

        coroutineScope.launch {
            val offerObserver = CoroutineSdpObserver()
            publisher.peerConnection.createOffer(offerObserver, OFFER_CONSTRAINTS)
            val sdpOffer = when (val outcome = offerObserver.awaitCreate()) {
                is Either.Left -> outcome.value
                is Either.Right -> {
                    Timber.d { "error creating offer: ${outcome.value}" }
                    return@launch
                }
            }

            val setObserver = CoroutineSdpObserver()
            publisher.peerConnection.setLocalDescription(setObserver, sdpOffer)
            when (val outcome = setObserver.awaitSet()) {
                is Either.Left -> client.sendOffer(sdpOffer)
                is Either.Right -> Timber.d { "error setting local description: ${outcome.value}" }
            }
        }
    }

    override fun onAnswer(sessionDescription: SessionDescription) {
        Timber.v { "received server answer: ${sessionDescription.type}, ${publisher.peerConnection.signalingState()}" }
        val observer = CoroutineSdpObserver()
        publisher.peerConnection.setRemoteDescription(observer, sessionDescription)
        coroutineScope.launch {
            when (val outcome = observer.awaitSet()) {
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
                val observer = CoroutineSdpObserver()
                subscriber.peerConnection.setRemoteDescription(observer, sessionDescription)
                when (val outcome = observer.awaitSet()) {
                    is Either.Right -> {
                        Timber.e { "error setting remote description for answer: ${outcome.value} " }
                        return@launch
                    }
                }
            }

            val answer = run {
                val observer = CoroutineSdpObserver()
                subscriber.peerConnection.createAnswer(observer, OFFER_CONSTRAINTS)
                when (val outcome = observer.awaitCreate()) {
                    is Either.Left -> outcome.value
                    is Either.Right -> {
                        Timber.e { "error creating answer: ${outcome.value}" }
                        return@launch
                    }
                }
            }

            run<Unit> {
                val observer = CoroutineSdpObserver()
                subscriber.peerConnection.setLocalDescription(observer, answer)
                when (val outcome = observer.awaitCreate()) {
                    is Either.Left -> client.sendAnswer(answer)
                    is Either.Right -> {
                        Timber.e { "error setting local description for answer: ${outcome.value}" }
                    }
                }
            }
        }
    }

    override fun onTrickle(candidate: IceCandidate, target: Rtc.SignalTarget) {
        Timber.v { "received ice candidate from peer: $candidate, $target" }
        when (target) {
            Rtc.SignalTarget.PUBLISHER -> publisher.addIceCandidate(candidate)
            Rtc.SignalTarget.SUBSCRIBER -> publisher.addIceCandidate(candidate)
            else -> Timber.i { "unknown ice candidate target?" }
        }
    }

    override fun onLocalTrackPublished(response: Rtc.TrackPublishedResponse) {
        val cid = response.cid ?: run {
            Timber.e { "local track published with null cid?" }
            return
        }

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
        listener?.onPublishLocalTrack(cid, track)

    }

    override fun onParticipantUpdate(updates: List<Model.ParticipantInfo>) {
        listener?.onUpdateParticipants(updates)
    }

    override fun onActiveSpeakersChanged(speakers: List<Rtc.SpeakerInfo>) {
        listener?.onUpdateSpeakers(speakers)
    }

    override fun onClose(reason: String, code: Int) {
        Timber.i { "received close event: $reason, code: $code" }
        listener?.onDisconnect(reason)
    }

    override fun onError(error: Exception) {
        listener?.onFailToConnect(error)
    }
}