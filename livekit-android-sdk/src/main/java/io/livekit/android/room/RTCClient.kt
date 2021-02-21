package io.livekit.android.room

import com.github.ajalt.timberkt.Timber
import com.google.protobuf.util.JsonFormat
import livekit.Model
import livekit.Rtc
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.webrtc.SessionDescription
import javax.inject.Inject

internal class RTCClient
@Inject
constructor(
    private val websocketFactory: WebSocket.Factory,
    private val fromJson: JsonFormat.Parser,
    private val toJson: JsonFormat.Printer,
) : WebSocketListener() {

    private var isConnected = false
    private var currentWs: WebSocket? = null
    var listener: Listener? = null

    fun connect(
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
//            Rtc.SignalResponse.MessageCase.TRICKLE -> {
//                TODO()
//            }
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

        //fun onTrickle(candidate: RTCIceCandidate, target: Rtc.SignalTarget)
        fun onLocalTrackPublished(trackPublished: Rtc.TrackPublishedResponse)
        fun onParticipantUpdate(updates: List<Model.ParticipantInfo>)
        fun onClose(reason: String, code: Int)
        fun onError(error: Error)
    }

    companion object {
        const val SD_TYPE_ANSWER = "answer"
        const val SD_TYPE_OFFER = "offer"
        const val SD_TYPE_PRANSWER = "pranswer"
    }
}