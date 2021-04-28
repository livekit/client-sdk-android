package io.livekit.android.room

import com.github.ajalt.timberkt.Timber
import com.google.protobuf.util.JsonFormat
import io.livekit.android.dagger.InjectionNames
import io.livekit.android.util.safe
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import livekit.LivekitModels
import livekit.LivekitRtc
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import javax.inject.Inject
import javax.inject.Named

/**
 * @suppress
 */
class RTCClient
@Inject
constructor(
    private val websocketFactory: WebSocket.Factory,
    private val fromJsonProtobuf: JsonFormat.Parser,
    private val toJsonProtobuf: JsonFormat.Printer,
    private val json: Json,
    @Named(InjectionNames.SIGNAL_JSON_ENABLED)
    private val useJson: Boolean,
) : WebSocketListener() {
    var isConnected = false
        private set
    private var currentWs: WebSocket? = null
    var listener: Listener? = null

    fun join(
        url: String,
        token: String,
    ) {
        val wsUrlString = "$url/rtc?protocol=$PROTOCOL_VERSION&access_token=$token"
        Timber.i { "connecting to $wsUrlString" }

        val request = Request.Builder()
            .url(wsUrlString)
            .build()
        currentWs = websocketFactory.newWebSocket(request, this)
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        Timber.v { response.message }
        super.onOpen(webSocket, response)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        Timber.v { text }
        val signalResponseBuilder = LivekitRtc.SignalResponse.newBuilder()
        fromJsonProtobuf.merge(text, signalResponseBuilder)
        val response = signalResponseBuilder.build()

        handleSignalResponse(response)
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        val byteArray = bytes.toByteArray()
        val signalResponseBuilder = LivekitRtc.SignalResponse.newBuilder()
            .mergeFrom(byteArray)
        val response = signalResponseBuilder.build()

        handleSignalResponse(response)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        Timber.v { "websocket closed" }
        super.onClosed(webSocket, code, reason)
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        Timber.v { "websocket closing" }
        super.onClosing(webSocket, code, reason)
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        Timber.v(t) { "websocket failure: ${response}" }

        super.onFailure(webSocket, t, response)
    }


    fun fromProtoSessionDescription(sd: LivekitRtc.SessionDescription): SessionDescription {
        val rtcSdpType = when (sd.type) {
            SD_TYPE_ANSWER -> SessionDescription.Type.ANSWER
            SD_TYPE_OFFER -> SessionDescription.Type.OFFER
            SD_TYPE_PRANSWER -> SessionDescription.Type.PRANSWER
            else -> throw IllegalArgumentException("invalid RTC SdpType: ${sd.type}")
        }
        return SessionDescription(rtcSdpType, sd.sdp)
    }

    fun toProtoSessionDescription(sdp: SessionDescription): LivekitRtc.SessionDescription {
        val sdBuilder = LivekitRtc.SessionDescription.newBuilder()
        sdBuilder.sdp = sdp.description
        sdBuilder.type = when (sdp.type) {
            SessionDescription.Type.ANSWER -> SD_TYPE_ANSWER
            SessionDescription.Type.OFFER -> SD_TYPE_OFFER
            SessionDescription.Type.PRANSWER -> SD_TYPE_PRANSWER
            else -> throw IllegalArgumentException("invalid RTC SdpType: ${sdp.type}")
        }

        return sdBuilder.build()
    }

    fun sendOffer(offer: SessionDescription) {
        val sd = toProtoSessionDescription(offer)
        val request = LivekitRtc.SignalRequest.newBuilder()
            .setOffer(sd)
            .build()

        sendRequest(request)
    }

    fun sendAnswer(answer: SessionDescription) {
        val sd = toProtoSessionDescription(answer)
        val request = LivekitRtc.SignalRequest.newBuilder()
            .setAnswer(sd)
            .build()

        sendRequest(request)
    }

    fun sendCandidate(candidate: IceCandidate, target: LivekitRtc.SignalTarget){
        val iceCandidateJSON = IceCandidateJSON(
            candidate = candidate.sdp,
            sdpMid = candidate.sdpMid,
            sdpMLineIndex = candidate.sdpMLineIndex
        )

        val trickleRequest = LivekitRtc.TrickleRequest.newBuilder()
            .setCandidateInit(json.encodeToString(iceCandidateJSON))
            .setTarget(target)
            .build()

        val request = LivekitRtc.SignalRequest.newBuilder()
            .setTrickle(trickleRequest)
            .build()

        sendRequest(request)
    }

    fun sendMuteTrack(trackSid: String, muted: Boolean) {
        val muteRequest = LivekitRtc.MuteTrackRequest.newBuilder()
            .setSid(trackSid)
            .setMuted(muted)
            .build()

        val request = LivekitRtc.SignalRequest.newBuilder()
            .setMute(muteRequest)
            .build()

        sendRequest(request)
    }

    fun sendAddTrack(cid: String, name: String, type: LivekitModels.TrackType) {
        val addTrackRequest = LivekitRtc.AddTrackRequest.newBuilder()
            .setCid(cid)
            .setName(name)
            .setType(type)
            .build()

        val request = LivekitRtc.SignalRequest.newBuilder()
            .setAddTrack(addTrackRequest)
            .build()

        sendRequest(request)
    }

    fun sendUpdateTrackSettings(sid: String, disabled: Boolean, videoQuality: LivekitRtc.VideoQuality) {
        val trackSettings = LivekitRtc.UpdateTrackSettings.newBuilder()
            .addTrackSids(sid)
            .setDisabled(disabled)
            .setQuality(videoQuality)

         val request = LivekitRtc.SignalRequest.newBuilder()
            .setTrackSetting(trackSettings)
            .build()

        sendRequest(request)
    }

    fun sendUpdateSubscription(sid: String, subscribe: Boolean, videoQuality: LivekitRtc.VideoQuality) {
        val subscription = LivekitRtc.UpdateSubscription.newBuilder()
            .addTrackSids(sid)
            .setSubscribe(subscribe)
            .setQuality(videoQuality)

         val request = LivekitRtc.SignalRequest.newBuilder()
            .setSubscription(subscription)
            .build()

        sendRequest(request)
    }
    
    fun sendLeave() {
        val request = LivekitRtc.SignalRequest.newBuilder()
            .setLeave(LivekitRtc.LeaveRequest.newBuilder().build())
            .build()
        sendRequest(request)
    }

    private fun sendRequest(request: LivekitRtc.SignalRequest) {
        Timber.v { "sending request: $request" }
        if (!isConnected || currentWs == null) {
            throw IllegalStateException("not connected!")
        }
        val sent: Boolean
        if (useJson) {
            val message = toJsonProtobuf.print(request)
            sent = currentWs?.send(message) ?: false
        } else {
            val message = request.toByteArray().toByteString()
            sent = currentWs?.send(message) ?: false
        }

        if (!sent) {
            Timber.e { "error sending request: $request" }
        }
    }

    private fun handleSignalResponse(response: LivekitRtc.SignalResponse) {
        if (!isConnected) {
            // Only handle joins if not connected.
            if (response.hasJoin()) {
                isConnected = true
                listener?.onJoin(response.join)
            } else {
                Timber.e { "Received response while not connected. ${toJsonProtobuf.print(response)}" }
            }
            return
        }

        Timber.v { "response: $response" }
        when (response.messageCase) {
            LivekitRtc.SignalResponse.MessageCase.ANSWER -> {
                val sd = fromProtoSessionDescription(response.answer)
                listener?.onAnswer(sd)
            }
            LivekitRtc.SignalResponse.MessageCase.OFFER -> {
                val sd = fromProtoSessionDescription(response.offer)
                listener?.onOffer(sd)
            }
            LivekitRtc.SignalResponse.MessageCase.TRICKLE -> {
                val iceCandidateJson =
                    json.decodeFromString<IceCandidateJSON>(response.trickle.candidateInit)
                val iceCandidate = IceCandidate(
                    iceCandidateJson.sdpMid,
                    iceCandidateJson.sdpMLineIndex,
                    iceCandidateJson.candidate
                )
                listener?.onTrickle(iceCandidate, response.trickle.target)
            }
            LivekitRtc.SignalResponse.MessageCase.UPDATE -> {
                listener?.onParticipantUpdate(response.update.participantsList)
            }
            LivekitRtc.SignalResponse.MessageCase.TRACK_PUBLISHED -> {
                listener?.onLocalTrackPublished(response.trackPublished)
            }
            LivekitRtc.SignalResponse.MessageCase.SPEAKER -> {
                listener?.onActiveSpeakersChanged(response.speaker.speakersList)
            }
            LivekitRtc.SignalResponse.MessageCase.JOIN -> {
                Timber.d { "received unexpected extra join message?" }
            }
            LivekitRtc.SignalResponse.MessageCase.LEAVE -> {
                listener?.onLeave()
            }
            LivekitRtc.SignalResponse.MessageCase.MESSAGE_NOT_SET,
            null -> {
                Timber.v { "empty messageCase!" }
            }
        }.safe()
    }

    fun close() {
        isConnected = false
        currentWs?.close(1000, "Normal Closure")
    }

    interface Listener {
        fun onJoin(info: LivekitRtc.JoinResponse)
        fun onAnswer(sessionDescription: SessionDescription)
        fun onOffer(sessionDescription: SessionDescription)
        fun onTrickle(candidate: IceCandidate, target: LivekitRtc.SignalTarget)
        fun onLocalTrackPublished(response: LivekitRtc.TrackPublishedResponse)
        fun onParticipantUpdate(updates: List<LivekitModels.ParticipantInfo>)
        fun onActiveSpeakersChanged(speakers: List<LivekitRtc.SpeakerInfo>)
        fun onClose(reason: String, code: Int)
        fun onLeave()
        fun onError(error: Exception)
    }

    companion object {
        const val SD_TYPE_ANSWER = "answer"
        const val SD_TYPE_OFFER = "offer"
        const val SD_TYPE_PRANSWER = "pranswer"
        const val PROTOCOL_VERSION = 2;

        private fun iceServer(url: String) =
            PeerConnection.IceServer.builder(url).createIceServer()

        // more stun servers might slow it down, WebRTC recommends 3 max
        val DEFAULT_ICE_SERVERS = listOf(
            iceServer("stun:stun.l.google.com:19302"),
            iceServer("stun:stun1.l.google.com:19302"),
//            iceServer("stun:stun2.l.google.com:19302"),
//            iceServer("stun:stun3.l.google.com:19302"),
//            iceServer("stun:stun4.l.google.com:19302"),
        )
    }
}