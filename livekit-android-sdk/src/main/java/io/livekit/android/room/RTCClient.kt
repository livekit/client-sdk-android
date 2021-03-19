package io.livekit.android.room

import com.github.ajalt.timberkt.Timber
import com.google.protobuf.util.JsonFormat
import io.livekit.android.dagger.InjectionNames
import io.livekit.android.room.track.Track
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
import okio.ByteString.Companion.toByteString
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import javax.inject.Inject
import javax.inject.Named

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
        Timber.v { response.message }
        super.onOpen(webSocket, response)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        Timber.v { text }
        val signalResponseBuilder = Rtc.SignalResponse.newBuilder()
        fromJsonProtobuf.merge(text, signalResponseBuilder)
        val response = signalResponseBuilder.build()

        handleSignalResponse(response)
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        val byteArray = bytes.toByteArray()
        val signalResponseBuilder = Rtc.SignalResponse.newBuilder()
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
            candidate = candidate.sdp,
            sdpMid = candidate.sdpMid,
            sdpMLineIndex = candidate.sdpMLineIndex
        )

        val trickleRequest = Rtc.TrickleRequest.newBuilder()
            .setCandidateInit(json.encodeToString(iceCandidateJSON))
            .setTarget(target)
            .build()

        val request = Rtc.SignalRequest.newBuilder()
            .setTrickle(trickleRequest)
            .build()

        sendRequest(request)
    }

    fun sendMuteTrack(trackSid: Track.Sid, muted: Boolean) {
        val muteRequest = Rtc.MuteTrackRequest.newBuilder()
            .setSid(trackSid.sid)
            .setMuted(muted)
            .build()

        val request = Rtc.SignalRequest.newBuilder()
            .setMute(muteRequest)
            .build()

        sendRequest(request)
    }

    fun sendAddTrack(cid: Track.Cid, name: String, type: Model.TrackType) {
        val addTrackRequest = Rtc.AddTrackRequest.newBuilder()
            .setCid(cid.cid)
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
            Timber.d { "error sending request: $request" }
            throw IllegalStateException()
        }
    }

    fun handleSignalResponse(response: Rtc.SignalResponse) {
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
                    json.decodeFromString<IceCandidateJSON>(response.trickle.candidateInit)
                val iceCandidate = IceCandidate(
                    iceCandidateJson.sdpMid,
                    iceCandidateJson.sdpMLineIndex,
                    iceCandidateJson.candidate
                )
                listener?.onTrickle(iceCandidate, response.trickle.target)
            }
            Rtc.SignalResponse.MessageCase.UPDATE -> {
                listener?.onParticipantUpdate(response.update.participantsList)
            }
            Rtc.SignalResponse.MessageCase.TRACK_PUBLISHED -> {
                listener?.onLocalTrackPublished(response.trackPublished)
            }
            Rtc.SignalResponse.MessageCase.SPEAKER -> {
                listener?.onActiveSpeakersChanged(response.speaker.speakersList)
            }
            Rtc.SignalResponse.MessageCase.MESSAGE_NOT_SET -> TODO()
            else -> {
                Timber.v { "unhandled response type: ${response.messageCase.name}" }
            }
        }
    }

    fun close() {
        isConnected = false
        currentWs?.close(1000, "Normal Closure")
    }

    interface Listener {
        fun onJoin(info: Rtc.JoinResponse)
        fun onAnswer(sessionDescription: SessionDescription)
        fun onOffer(sessionDescription: SessionDescription)
        fun onTrickle(candidate: IceCandidate, target: Rtc.SignalTarget)
        fun onLocalTrackPublished(response: Rtc.TrackPublishedResponse)
        fun onParticipantUpdate(updates: List<Model.ParticipantInfo>)
        fun onActiveSpeakersChanged(speakers: List<Rtc.SpeakerInfo>)
        fun onClose(reason: String, code: Int)
        fun onError(error: Exception)
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