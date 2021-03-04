package io.livekit.android.room

import livekit.Model
import livekit.Rtc
import org.webrtc.*
import javax.inject.Inject

class RTCEngine
@Inject
constructor(
    val client: RTCClient,
    pctFactory: PeerConnectionTransport.Factory,
) {

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

    private val publisherObserver = PublisherTransportObserver(this)
    private val subscriberObserver = SubscriberTransportObserver(this)
    private val publisher: PeerConnectionTransport
    private val subscriber: PeerConnectionTransport

    init {
        val rtcConfig = PeerConnection.RTCConfiguration(RTCClient.DEFAULT_ICE_SERVERS).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        publisher = pctFactory.create(rtcConfig, publisherObserver)
        subscriber = pctFactory.create(rtcConfig, subscriberObserver)

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
}