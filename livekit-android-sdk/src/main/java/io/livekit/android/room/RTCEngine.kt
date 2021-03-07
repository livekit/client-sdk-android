package io.livekit.android.room

import android.content.Context
import io.livekit.android.room.track.Track
import livekit.Model
import livekit.Rtc
import org.webrtc.*
import javax.inject.Inject
import kotlin.coroutines.Continuation


class RTCEngine
@Inject
constructor(
    private val appContext: Context,
    val client: RTCClient,
    pctFactory: PeerConnectionTransport.Factory,
) : RTCClient.Listener {

    var listener: Listener? = null
    var rtcConnected: Boolean = false
    var joinResponse: Rtc.JoinResponse? = null
    var iceConnected: Boolean = false
        set(value) {
            field = value
            if (field) {
                listener?.onJoin(joinResponse)
                joinResponse = null
            }
        }
    val pendingCandidates = mutableListOf<IceCandidate>()
    private val pendingTrackResolvers: MutableMap<Track.Cid, Continuation<Model.TrackInfo>> =
        mutableMapOf()

    private val publisherObserver = PublisherTransportObserver(this)
    private val subscriberObserver = SubscriberTransportObserver(this)
    private val publisher: PeerConnectionTransport
    private val subscriber: PeerConnectionTransport

    private var privateDataChannel: DataChannel

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

    suspend fun join(url: String, token: String, isSecure: Boolean) {
        client.join(url, token, isSecure)
    }

    fun negotiate() {
        TODO("Not yet implemented")
    }

    interface Listener {
        fun onJoin(response: Rtc.JoinResponse?)
        fun onAddTrack(track: MediaStreamTrack, streams: Array<out MediaStream>)
        fun onPublishLocalTrack(cid: String, track: Model.TrackInfo)
        fun onAddDataChannel(channel: DataChannel)
        fun onUpdateParticipants(updates: Array<out Model.ParticipantInfo>)
        fun onUpdateSpeakers(speakers: Array<out Rtc.SpeakerInfo>)
        fun onDisconnect(reason: String)
        fun onFailToConnect(error: Error)
    }

    companion object {
        private const val PRIVATE_DATA_CHANNEL_LABEL = "_private"
    }

    override fun onJoin(info: Rtc.JoinResponse) {
        TODO("Not yet implemented")
    }

    override fun onAnswer(sessionDescription: SessionDescription) {
        TODO("Not yet implemented")
    }

    override fun onOffer(sessionDescription: SessionDescription) {
        TODO("Not yet implemented")
    }

    override fun onTrickle(candidate: IceCandidate, target: Rtc.SignalTarget) {
        TODO("Not yet implemented")
    }

    override fun onLocalTrackPublished(trackPublished: Rtc.TrackPublishedResponse) {
        TODO("Not yet implemented")
    }

    override fun onParticipantUpdate(updates: List<Model.ParticipantInfo>) {
        TODO("Not yet implemented")
    }

    override fun onClose(reason: String, code: Int) {
        TODO("Not yet implemented")
    }

    override fun onError(error: Error) {
        TODO("Not yet implemented")
    }
}