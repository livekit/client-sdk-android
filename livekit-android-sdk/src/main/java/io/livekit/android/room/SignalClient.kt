package io.livekit.android.room

import com.vdurmont.semver4j.Semver
import io.livekit.android.ConnectOptions
import io.livekit.android.RoomOptions
import io.livekit.android.dagger.InjectionNames
import io.livekit.android.room.participant.ParticipantTrackPermission
import io.livekit.android.room.track.Track
import io.livekit.android.stats.getClientInfo
import io.livekit.android.util.CloseableCoroutineScope
import io.livekit.android.util.Either
import io.livekit.android.util.LKLog
import io.livekit.android.util.safe
import io.livekit.android.webrtc.toProtoSessionDescription
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import livekit.LivekitModels
import livekit.LivekitRtc
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * SignalClient to LiveKit WS servers
 * @suppress
 */
@Singleton
class SignalClient
@Inject
constructor(
    private val websocketFactory: WebSocket.Factory,
    private val json: Json,
    private val okHttpClient: OkHttpClient,
    @Named(InjectionNames.DISPATCHER_IO)
    private val ioDispatcher: CoroutineDispatcher,
) : WebSocketListener() {
    var isConnected = false
        private set
    private var currentWs: WebSocket? = null
    private var isReconnecting: Boolean = false
    var listener: Listener? = null
    private var serverVersion: Semver? = null
    private var lastUrl: String? = null
    private var lastOptions: ConnectOptions? = null
    private var lastRoomOptions: RoomOptions? = null

    private var joinContinuation: CancellableContinuation<Either<LivekitRtc.JoinResponse, Unit>>? = null
    private lateinit var coroutineScope: CloseableCoroutineScope

    private val requestFlowJobLock = Object()
    private var requestFlowJob: Job? = null
    private val requestFlow = MutableSharedFlow<LivekitRtc.SignalRequest>(Int.MAX_VALUE)

    private val responseFlow = MutableSharedFlow<LivekitRtc.SignalResponse>(Int.MAX_VALUE)


    /**
     * @throws Exception if fails to connect.
     */
    suspend fun join(
        url: String,
        token: String,
        options: ConnectOptions = ConnectOptions(),
        roomOptions: RoomOptions = RoomOptions(),
    ): LivekitRtc.JoinResponse {
        val joinResponse = connect(url, token, options, roomOptions)
        return (joinResponse as Either.Left).value
    }

    /**
     * @throws Exception if fails to connect.
     */
    suspend fun reconnect(url: String, token: String) {
        connect(
            url,
            token,
            (lastOptions ?: ConnectOptions()).copy()
                .apply { reconnect = true },
            lastRoomOptions ?: RoomOptions()
        )
    }

    suspend fun connect(
        url: String,
        token: String,
        options: ConnectOptions,
        roomOptions: RoomOptions
    ): Either<LivekitRtc.JoinResponse, Unit> {
        // Clean up any pre-existing connection.
        close()

        val wsUrlString = "$url/rtc" + createConnectionParams(token, getClientInfo(), options, roomOptions)
        isReconnecting = options.reconnect

        LKLog.i { "connecting to $wsUrlString" }

        coroutineScope = CloseableCoroutineScope(SupervisorJob() + ioDispatcher)
        lastUrl = wsUrlString
        lastOptions = options
        lastRoomOptions = roomOptions

        val request = Request.Builder()
            .url(wsUrlString)
            .build()

        return suspendCancellableCoroutine {
            // Wait for join response through WebSocketListener
            joinContinuation = it
            currentWs = websocketFactory.newWebSocket(request, this)
        }
    }

    private fun createConnectionParams(
        token: String,
        clientInfo: LivekitModels.ClientInfo,
        options: ConnectOptions,
        roomOptions: RoomOptions
    ): String {

        val queryParams = mutableListOf<Pair<String, String>>()
        queryParams.add(CONNECT_QUERY_TOKEN to token)
        queryParams.add(CONNECT_QUERY_PROTOCOL to options.protocolVersion.value.toString())

        if (options.reconnect) {
            queryParams.add(CONNECT_QUERY_RECONNECT to 1.toString())
        }

        val autoSubscribe = if (options.autoSubscribe) 1 else 0
        queryParams.add(CONNECT_QUERY_AUTOSUBSCRIBE to autoSubscribe.toString())

        val adaptiveStream = if (roomOptions.adaptiveStream) 1 else 0
        queryParams.add(CONNECT_QUERY_ADAPTIVE_STREAM to adaptiveStream.toString())

        // Client info
        queryParams.add(CONNECT_QUERY_SDK to "android")
        queryParams.add(CONNECT_QUERY_VERSION to clientInfo.version)
        queryParams.add(CONNECT_QUERY_DEVICE_MODEL to clientInfo.deviceModel)
        queryParams.add(CONNECT_QUERY_OS to clientInfo.os)
        queryParams.add(CONNECT_QUERY_OS_VERSION to clientInfo.osVersion)

        return queryParams.foldIndexed("") { index, acc, pair ->
            val separator = if(index == 0) "?" else "&"
            acc + separator + "${pair.first}=${pair.second}"
        }
    }

    /**
     * Notifies that the downstream consumers of SignalClient are ready to consume messages.
     * Until this method is called, any messages received through the websocket are buffered.
     *
     * Should be called after resolving the join message.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun onReadyForResponses() {
        coroutineScope.launch {
            responseFlow.collect {
                responseFlow.resetReplayCache()
                handleSignalResponseImpl(it)
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun startRequestQueue() {
        if (requestFlowJob != null) {
            return
        }
        synchronized(requestFlowJobLock) {
            if (requestFlowJob == null) {
                requestFlowJob = coroutineScope.launch {
                    requestFlow.collect {
                        requestFlow.resetReplayCache()
                        sendRequestImpl(it)
                    }
                }
            }
        }
    }

    /**
     * On reconnection, SignalClient waits until the peer connection is established to send messages.
     * Call this method when it is connected.
     */
    fun onPCConnected() {
        startRequestQueue()
    }

    //--------------------------------- WebSocket Listener --------------------------------------//
    override fun onOpen(webSocket: WebSocket, response: Response) {
        if (isReconnecting) {
            // no need to wait for join response on reconnection.
            isReconnecting = false
            isConnected = true
            joinContinuation?.resumeWith(Result.success(Either.Right(Unit)))
        }
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        LKLog.w { "received JSON message, unsupported in this version." }
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        val byteArray = bytes.toByteArray()
        val signalResponseBuilder = LivekitRtc.SignalResponse.newBuilder()
            .mergeFrom(byteArray)
        val response = signalResponseBuilder.build()

        handleSignalResponse(response)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        handleWebSocketClose(reason, code)
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        LKLog.v { "websocket closing" }
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        var reason: String? = null
        try {
            lastUrl?.let {
                val validationUrl = "http" + it.substring(2).replaceFirst("/rtc?", "/rtc/validate?")
                val request = Request.Builder().url(validationUrl).build()
                val resp = okHttpClient.newCall(request).execute()
                if (!resp.isSuccessful) {
                    reason = resp.body?.string()
                }
            }
        } catch (e: Throwable) {
            LKLog.e(e) { "failed to validate connection" }
        }

        if (reason != null) {
            LKLog.e(t) { "websocket failure: $reason" }
            val error = Exception(reason)
            listener?.onError(error)
            joinContinuation?.cancel(error)
        } else {
            LKLog.e(t) { "websocket failure: $response" }
            listener?.onError(t)
            joinContinuation?.cancel(t)
        }

        val wasConnected = isConnected
        isConnected = false

        if (wasConnected) {
            handleWebSocketClose(
                reason = reason ?: response?.toString() ?: t.localizedMessage ?: "websocket failure",
                code = response?.code ?: 500
            )
        }
    }

    private fun handleWebSocketClose(reason: String, code: Int) {
        LKLog.v { "websocket closed" }
        listener?.onClose(reason, code)
    }

    //------------------------------- End WebSocket Listener ------------------------------------//

    private fun fromProtoSessionDescription(sd: LivekitRtc.SessionDescription): SessionDescription {
        val rtcSdpType = when (sd.type) {
            SD_TYPE_ANSWER -> SessionDescription.Type.ANSWER
            SD_TYPE_OFFER -> SessionDescription.Type.OFFER
            SD_TYPE_PRANSWER -> SessionDescription.Type.PRANSWER
            else -> throw IllegalArgumentException("invalid RTC SdpType: ${sd.type}")
        }
        return SessionDescription(rtcSdpType, sd.sdp)
    }

    fun sendOffer(offer: SessionDescription) {
        val sd = offer.toProtoSessionDescription()
        val request = LivekitRtc.SignalRequest.newBuilder()
            .setOffer(sd)
            .build()

        sendRequest(request)
    }

    fun sendAnswer(answer: SessionDescription) {
        val sd = answer.toProtoSessionDescription()
        val request = LivekitRtc.SignalRequest.newBuilder()
            .setAnswer(sd)
            .build()

        sendRequest(request)
    }

    fun sendCandidate(candidate: IceCandidate, target: LivekitRtc.SignalTarget) {
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

    /**
     * @param builder an optional builder to include other parameters related to the track
     */
    fun sendAddTrack(
        cid: String,
        name: String,
        type: LivekitModels.TrackType,
        builder: LivekitRtc.AddTrackRequest.Builder = LivekitRtc.AddTrackRequest.newBuilder()
    ) {
        val addTrackRequest = builder
            .setCid(cid)
            .setName(name)
            .setType(type)
        val request = LivekitRtc.SignalRequest.newBuilder()
            .setAddTrack(addTrackRequest)
            .build()

        sendRequest(request)
    }

    fun sendUpdateTrackSettings(
        sid: String,
        disabled: Boolean,
        videoDimensions: Track.Dimensions?,
        videoQuality: LivekitModels.VideoQuality?,
    ) {
        val trackSettings = LivekitRtc.UpdateTrackSettings.newBuilder()
            .addTrackSids(sid)
            .setDisabled(disabled)
            .apply {
                if (videoDimensions != null) {
                    width = videoDimensions.width
                    height = videoDimensions.height
                } else if (videoQuality != null) {
                    quality = videoQuality
                } else {
                    // default to HIGH
                    quality = LivekitModels.VideoQuality.HIGH
                }
            }

        val request = LivekitRtc.SignalRequest.newBuilder()
            .setTrackSetting(trackSettings)
            .build()

        sendRequest(request)
    }

    fun sendUpdateSubscription(subscribe: Boolean, vararg participantTracks: LivekitModels.ParticipantTracks) {
        val participantTracksList = participantTracks.toList()

        // backwards compatibility for protocol version < 6
        val trackSids = participantTracksList.map { it.trackSidsList }.flatten()
        val subscription = LivekitRtc.UpdateSubscription.newBuilder()
            .addAllParticipantTracks(participantTracks.toList())
            .addAllTrackSids(trackSids)
            .setSubscribe(subscribe)

        val request = LivekitRtc.SignalRequest.newBuilder()
            .setSubscription(subscription)
            .build()

        sendRequest(request)
    }

    fun sendUpdateSubscriptionPermissions(
        allParticipants: Boolean,
        participantTrackPermissions: List<ParticipantTrackPermission>
    ) {
        val update = LivekitRtc.SubscriptionPermission.newBuilder()
            .setAllParticipants(allParticipants)
            .addAllTrackPermissions(participantTrackPermissions.map { it.toProto() })

        val request = LivekitRtc.SignalRequest.newBuilder()
            .setSubscriptionPermission(update)
            .build()

        sendRequest(request)
    }

    fun sendSyncState(syncState: LivekitRtc.SyncState) {
        val request = LivekitRtc.SignalRequest.newBuilder()
            .setSyncState(syncState)
            .build()

        sendRequest(request)
    }

    internal fun sendSimulateScenario(scenario: LivekitRtc.SimulateScenario) {
        val request = LivekitRtc.SignalRequest.newBuilder()
            .setSimulate(scenario)
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
        val skipQueue = skipQueueTypes.contains(request.messageCase)

        if (skipQueue) {
            sendRequestImpl(request)
        } else {
            requestFlow.tryEmit(request)
        }
    }

    private fun sendRequestImpl(request: LivekitRtc.SignalRequest) {
        LKLog.v { "sending request: $request" }
        if (!isConnected || currentWs == null) {
            LKLog.w { "not connected, could not send request $request" }
            return
        }
        val message = request.toByteArray().toByteString()
        val sent = currentWs?.send(message) ?: false

        if (!sent) {
            LKLog.e { "error sending request: $request" }
        }
    }

    private fun handleSignalResponse(response: LivekitRtc.SignalResponse) {
        LKLog.v { "response: $response" }

        if (!isConnected) {
            // Only handle joins if not connected.
            if (response.join != null) {
                isConnected = true
                startRequestQueue()
                try {
                    serverVersion = Semver(response.join.serverVersion)
                } catch (t: Throwable) {
                    LKLog.w(t) { "Thrown while trying to parse server version." }
                }
                joinContinuation?.resumeWith(Result.success(Either.Left(response.join)))
            } else {
                LKLog.e { "Received response while not connected. $response" }
            }
            return
        }
        responseFlow.tryEmit(response)
    }

    private fun handleSignalResponseImpl(response: LivekitRtc.SignalResponse) {
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
            LivekitRtc.SignalResponse.MessageCase.SPEAKERS_CHANGED -> {
                listener?.onSpeakersChanged(response.speakersChanged.speakersList)
            }
            LivekitRtc.SignalResponse.MessageCase.JOIN -> {
                LKLog.d { "received unexpected extra join message?" }
            }
            LivekitRtc.SignalResponse.MessageCase.LEAVE -> {
                listener?.onLeave(response.leave)
            }
            LivekitRtc.SignalResponse.MessageCase.MUTE -> {
                listener?.onRemoteMuteChanged(response.mute.sid, response.mute.muted)
            }
            LivekitRtc.SignalResponse.MessageCase.ROOM_UPDATE -> {
                listener?.onRoomUpdate(response.roomUpdate.room)
            }
            LivekitRtc.SignalResponse.MessageCase.CONNECTION_QUALITY -> {
                listener?.onConnectionQuality(response.connectionQuality.updatesList)
            }
            LivekitRtc.SignalResponse.MessageCase.STREAM_STATE_UPDATE -> {
                listener?.onStreamStateUpdate(response.streamStateUpdate.streamStatesList)
            }
            LivekitRtc.SignalResponse.MessageCase.SUBSCRIBED_QUALITY_UPDATE -> {
                val versionToIgnoreUpTo = Semver("0.15.1")
                if (serverVersion?.compareTo(versionToIgnoreUpTo) ?: 1 <= 0) {
                    return
                }
                listener?.onSubscribedQualityUpdate(response.subscribedQualityUpdate)
            }
            LivekitRtc.SignalResponse.MessageCase.SUBSCRIPTION_PERMISSION_UPDATE -> {
                listener?.onSubscriptionPermissionUpdate(response.subscriptionPermissionUpdate)
            }
            LivekitRtc.SignalResponse.MessageCase.REFRESH_TOKEN -> {
                listener?.onRefreshToken(response.refreshToken)
            }
            LivekitRtc.SignalResponse.MessageCase.TRACK_UNPUBLISHED -> {
                listener?.onLocalTrackUnpublished(response.trackUnpublished)
            }
            LivekitRtc.SignalResponse.MessageCase.MESSAGE_NOT_SET,
            null -> {
                LKLog.v { "empty messageCase!" }
            }
        }.safe()
    }

    /**
     * Closes out any existing websocket connection, and cleans up used resources.
     *
     * Can be reused afterwards.
     */
    fun close(code: Int = 1000, reason: String = "Normal Closure") {
        isConnected = false
        isReconnecting = false
        requestFlowJob = null
        if (::coroutineScope.isInitialized) {
            coroutineScope.close()
        }
        currentWs?.close(code, reason)
        currentWs = null
        joinContinuation?.cancel()
        joinContinuation = null
        lastUrl = null
        lastOptions = null
        lastRoomOptions = null
    }

    interface Listener {
        fun onAnswer(sessionDescription: SessionDescription)
        fun onOffer(sessionDescription: SessionDescription)
        fun onTrickle(candidate: IceCandidate, target: LivekitRtc.SignalTarget)
        fun onLocalTrackPublished(response: LivekitRtc.TrackPublishedResponse)
        fun onParticipantUpdate(updates: List<LivekitModels.ParticipantInfo>)
        fun onSpeakersChanged(speakers: List<LivekitModels.SpeakerInfo>)
        fun onClose(reason: String, code: Int)
        fun onRemoteMuteChanged(trackSid: String, muted: Boolean)
        fun onRoomUpdate(update: LivekitModels.Room)
        fun onConnectionQuality(updates: List<LivekitRtc.ConnectionQualityInfo>)
        fun onLeave(leave: LivekitRtc.LeaveRequest)
        fun onError(error: Throwable)
        fun onStreamStateUpdate(streamStates: List<LivekitRtc.StreamStateInfo>)
        fun onSubscribedQualityUpdate(subscribedQualityUpdate: LivekitRtc.SubscribedQualityUpdate)
        fun onSubscriptionPermissionUpdate(subscriptionPermissionUpdate: LivekitRtc.SubscriptionPermissionUpdate)
        fun onRefreshToken(token: String)
        fun onLocalTrackUnpublished(trackUnpublished: LivekitRtc.TrackUnpublishedResponse)
    }

    companion object {
        const val CONNECT_QUERY_TOKEN = "access_token"
        const val CONNECT_QUERY_RECONNECT = "reconnect"
        const val CONNECT_QUERY_AUTOSUBSCRIBE = "auto_subscribe"
        const val CONNECT_QUERY_ADAPTIVE_STREAM = "adaptive_stream"
        const val CONNECT_QUERY_SDK = "sdk"
        const val CONNECT_QUERY_VERSION = "version"
        const val CONNECT_QUERY_PROTOCOL = "protocol"
        const val CONNECT_QUERY_DEVICE_MODEL = "device_model"
        const val CONNECT_QUERY_OS = "os"
        const val CONNECT_QUERY_OS_VERSION = "os_version"

        const val SD_TYPE_ANSWER = "answer"
        const val SD_TYPE_OFFER = "offer"
        const val SD_TYPE_PRANSWER = "pranswer"
        const val SDK_TYPE = "android"

        private val skipQueueTypes = listOf(
            LivekitRtc.SignalRequest.MessageCase.SYNC_STATE,
            LivekitRtc.SignalRequest.MessageCase.TRICKLE,
            LivekitRtc.SignalRequest.MessageCase.OFFER,
            LivekitRtc.SignalRequest.MessageCase.ANSWER,
            LivekitRtc.SignalRequest.MessageCase.SIMULATE
        )

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

enum class ProtocolVersion(val value: Int) {
    v1(1),
    v2(2),
    v3(3),
    v4(4),
    v5(5),
    v6(6),
    v7(7),
    v8(8),
}