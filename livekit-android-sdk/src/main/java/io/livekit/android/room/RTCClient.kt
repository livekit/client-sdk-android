package io.livekit.android.room

import com.github.ajalt.timberkt.Timber
import com.google.protobuf.util.JsonFormat
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import livekit.Model
import livekit.Rtc
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import javax.inject.Inject

class RTCClient
@Inject
constructor(
    private val websocketFactory: WebSocket.Factory,
    private val fromJson: JsonFormat.Parser,
    private val toJson: JsonFormat.Printer,
) : WebSocketListener() {

    private var isConnected = false
    private var currentWs: WebSocket? = null
    var listener: Listener? = null

    fun join(
        host: String,
        token: String,
        isSecure: Boolean,
    ) {
        val protocol = if (isSecure) "wss" else "ws"

        val wsUrlString = "$protocol://$host/rtc?access_token=$token"
        Timber.i { "connecting to $wsUrlString" }

        val request = Request.Builder()
            .url(wsUrlString)
            .build()
        currentWs = websocketFactory.newWebSocket(request, this)
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        super.onOpen(webSocket, response)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        val signalResponseBuilder = Rtc.SignalResponse.newBuilder()
        fromJson.merge(text, signalResponseBuilder)
        val response = signalResponseBuilder.build()

        if (!isConnected) {
            // Only handle joins if not connected.
            if (response.hasJoin()) {
                isConnected = true
                listener?.onJoin(response.join)
            } else {
                Timber.e { "out of order message?" }
            }
            return
        }
        handleSignalResponse(response)
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        super.onMessage(webSocket, bytes)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        super.onClosed(webSocket, code, reason)
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        super.onClosing(webSocket, code, reason)
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        super.onFailure(webSocket, t, response)
    }


    fun fromProtoSessionDescription(sd: Rtc.SessionDescription): SessionDescription {
        val rtcSdpType = when (sd.type) {
            SD_TYPE_ANSWER -> SessionDescription.Type.ANSWER
            SD_TYPE_OFFER -> SessionDescription.Type.OFFER
            SD_TYPE_PRANSWER -> SessionDescription.Type.PRANSWER
            else -> throw IllegalArgumentException("invalid RTC SdpType: ${sd.type}")
        }
        return SessionDescription(rtcSdpType, sd.sdp)
    }

    fun toProtoSessionDescription(sdp: SessionDescription): Rtc.SessionDescription {
        val sdBuilder = Rtc.SessionDescription.newBuilder()
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
        val request = Rtc.SignalRequest.newBuilder()
            .setOffer(sd)
            .build()

        sendRequest(request)
    }

    fun sendAnswer(answer: SessionDescription) {
        val sd = toProtoSessionDescription(answer)
        val request = Rtc.SignalRequest.newBuilder()
            .setAnswer(sd)
            .build()

        sendRequest(request)
    }

    fun sendCandidate(candidate: IceCandidate, target: Rtc.SignalTarget){
        val iceCandidateJSON = IceCandidateJSON(
            sdp = candidate.sdp,
            sdpMid = candidate.sdpMid,
            sdpMLineIndex = candidate.sdpMLineIndex
        )

        val trickleRequest = Rtc.TrickleRequest.newBuilder()
            .setCandidateInit(Json.encodeToString(iceCandidateJSON))
            .setTarget(target)
            .build()

        val request = Rtc.SignalRequest.newBuilder()
            .setTrickle(trickleRequest)
            .build()

        sendRequest(request)
    }

    fun sendMuteTrack(trackSid: String, muted: Boolean) {
        val muteRequest = Rtc.MuteTrackRequest.newBuilder()
            .setSid(trackSid)
            .setMuted(muted)
            .build()

        val request = Rtc.SignalRequest.newBuilder()
            .setMute(muteRequest)
            .build()

        sendRequest(request)
    }

    fun sendAddTrack(cid: String, name: String, type: Model.TrackType) {
        val addTrackRequest = Rtc.AddTrackRequest.newBuilder()
            .setCid(cid)
            .setName(name)
            .setType(type)
            .build()

        val request = Rtc.SignalRequest.newBuilder()
            .setAddTrack(addTrackRequest)
            .build()

        sendRequest(request)
    }

    fun sendRequest(request: Rtc.SignalRequest) {
        Timber.v { "sending request: $request" }
        if (!isConnected || currentWs != null) {
            throw IllegalStateException("not connected!")
        }
        val message = toJson.print(request)
        val sent = currentWs?.send(message) ?: false

        if (!sent) {
            Timber.d { "error sending request: $request" }
            throw IllegalStateException()
        }

    }

    fun handleSignalResponse(response: Rtc.SignalResponse) {
        if (!isConnected) {
            Timber.e { "Received response while not connected. ${toJson.print(response)}" }
            return
        }
        when (response.messageCase) {
            Rtc.SignalResponse.MessageCase.ANSWER -> {
                val sd = fromProtoSessionDescription(response.answer)
                listener?.onAnswer(sd)
            }
            Rtc.SignalResponse.MessageCase.OFFER -> {
                val sd = fromProtoSessionDescription(response.offer)
                listener?.onOffer(sd)
            }
            Rtc.SignalResponse.MessageCase.TRICKLE -> {
                val iceCandidateJson =
                    Json.decodeFromString<IceCandidateJSON>(response.trickle.candidateInit)
                val iceCandidate = IceCandidate(
                    iceCandidateJson.sdpMid,
                    iceCandidateJson.sdpMLineIndex,
                    iceCandidateJson.sdp
                )
                listener?.onTrickle(iceCandidate, response.trickle.target)
            }
            Rtc.SignalResponse.MessageCase.UPDATE -> {
                listener?.onParticipantUpdate(response.update.participantsList)
            }
            Rtc.SignalResponse.MessageCase.TRACK_PUBLISHED -> {
                listener?.onLocalTrackPublished(response.trackPublished)
            }
            Rtc.SignalResponse.MessageCase.SPEAKER -> TODO()
            Rtc.SignalResponse.MessageCase.MESSAGE_NOT_SET -> TODO()
            else -> {
                Timber.v { "unhandled response type: ${response.messageCase.name}" }
            }
        }
    }

    interface Listener {
        fun onJoin(info: Rtc.JoinResponse)

        fun onAnswer(sessionDescription: SessionDescription)
        fun onOffer(sessionDescription: SessionDescription)

        fun onTrickle(candidate: IceCandidate, target: Rtc.SignalTarget)
        fun onLocalTrackPublished(trackPublished: Rtc.TrackPublishedResponse)
        fun onParticipantUpdate(updates: List<Model.ParticipantInfo>)
        fun onClose(reason: String, code: Int)
        fun onError(error: Error)
    }

    companion object {
        const val SD_TYPE_ANSWER = "answer"
        const val SD_TYPE_OFFER = "offer"
        const val SD_TYPE_PRANSWER = "pranswer"

        private fun iceServer(url: String) =
            PeerConnection.IceServer.builder(url).createIceServer()

        val DEFAULT_ICE_SERVERS = listOf(
            iceServer("stun:stun.l.google.com:19302"),
            iceServer("stun:stun1.l.google.com:19302"),
            iceServer("stun:stun2.l.google.com:19302"),
            iceServer("stun:stun3.l.google.com:19302"),
            iceServer("stun:stun4.l.google.com:19302"),
        )
    }
}