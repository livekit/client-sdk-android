/*
 * Copyright 2023-2025 LiveKit, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.livekit.android.room

import androidx.annotation.VisibleForTesting
import com.vdurmont.semver4j.Semver
import io.livekit.android.ConnectOptions
import io.livekit.android.RoomOptions
import io.livekit.android.dagger.InjectionNames
import io.livekit.android.room.participant.ParticipantTrackPermission
import io.livekit.android.room.track.Track
import io.livekit.android.stats.NetworkInfo
import io.livekit.android.stats.getClientInfo
import io.livekit.android.util.CloseableCoroutineScope
import io.livekit.android.util.Either
import io.livekit.android.util.LKLog
import io.livekit.android.util.toHttpUrl
import io.livekit.android.util.toWebsocketUrl
import io.livekit.android.webrtc.toProtoSessionDescription
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import livekit.LivekitModels
import livekit.LivekitModels.AudioTrackFeature
import livekit.LivekitRtc
import livekit.LivekitRtc.JoinResponse
import livekit.LivekitRtc.ReconnectResponse
import livekit.org.webrtc.IceCandidate
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.SessionDescription
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.Date
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * SignalClient to LiveKit WS servers
 * @suppress
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class SignalClient
@Inject
constructor(
    private val websocketFactory: WebSocket.Factory,
    private val json: Json,
    private val okHttpClient: OkHttpClient,
    @Named(InjectionNames.DISPATCHER_IO)
    private val ioDispatcher: CoroutineDispatcher,
    private val networkInfo: NetworkInfo,
) : WebSocketListener() {
    var isConnected = false
        private set
    private var currentWs: WebSocket? = null
    private var isReconnecting: Boolean = false
    var listener: Listener? = null
    internal var serverVersion: Semver? = null
    private var lastUrl: String? = null
    private var lastOptions: ConnectOptions? = null
    private var lastRoomOptions: RoomOptions? = null

    // join will always return a JoinResponse.
    // reconnect will return a ReconnectResponse or a Unit if a different response was received.
    private var joinContinuation: CancellableContinuation<
        Either<
            JoinResponse,
            Either<ReconnectResponse, Unit>,
            >,
        >? = null
    private lateinit var coroutineScope: CloseableCoroutineScope

    /**
     * @see [startRequestQueue]
     */
    private val requestFlow = MutableSharedFlow<LivekitRtc.SignalRequest>(Int.MAX_VALUE)
    private val requestFlowJobLock = Object()
    private var requestFlowJob: Job? = null

    /**
     * @see [onReadyForResponses]
     */
    private val responseFlow = MutableSharedFlow<Pair<WebSocket, LivekitRtc.SignalResponse>>(Int.MAX_VALUE)
    private val responseFlowJobLock = Object()
    private var responseFlowJob: Job? = null

    private var pingJob: Job? = null
    private var pongJob: Job? = null
    private var pingTimeoutDurationMillis: Long = 0
    private var pingIntervalDurationMillis: Long = 0
    private var rtt: Long = 0

    var connectionState: ConnectionState = ConnectionState.DISCONNECTED

    /**
     * @throws Exception if fails to connect.
     */
    @Throws(Exception::class)
    suspend fun join(
        url: String,
        token: String,
        options: ConnectOptions = ConnectOptions(),
        roomOptions: RoomOptions = RoomOptions(),
    ): JoinResponse {
        val joinResponse = connect(url, token, options, roomOptions)
        return (joinResponse as Either.Left).value
    }

    /**
     * @throws Exception if fails to connect.
     */
    @Throws(Exception::class)
    @VisibleForTesting
    suspend fun reconnect(url: String, token: String, participantSid: String?): Either<ReconnectResponse, Unit> {
        val reconnectResponse = connect(
            url,
            token,
            (lastOptions ?: ConnectOptions()).copy()
                .apply {
                    reconnect = true
                    this.participantSid = participantSid
                },
            lastRoomOptions ?: RoomOptions(),
        )
        return (reconnectResponse as Either.Right).value
    }

    private suspend fun connect(
        url: String,
        token: String,
        options: ConnectOptions,
        roomOptions: RoomOptions,
    ): Either<JoinResponse, Either<ReconnectResponse, Unit>> {
        // Clean up any pre-existing connection.
        close(reason = "Starting new connection", shouldClearQueuedRequests = false)

        val wsUrlString = "${url.toWebsocketUrl()}/rtc" + createConnectionParams(token, getClientInfo(), options, roomOptions)
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
        roomOptions: RoomOptions,
    ): String {
        val queryParams = mutableListOf<Pair<String, String>>()
        queryParams.add(CONNECT_QUERY_TOKEN to token)
        queryParams.add(CONNECT_QUERY_PROTOCOL to options.protocolVersion.value.toString())

        if (options.reconnect) {
            queryParams.add(CONNECT_QUERY_RECONNECT to 1.toString())
            options.participantSid?.let { sid ->
                queryParams.add(CONNECT_QUERY_PARTICIPANT_SID to sid)
            }
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
        queryParams.add(CONNECT_QUERY_NETWORK_TYPE to networkInfo.getNetworkType().protoName)

        return queryParams.foldIndexed("") { index, acc, pair ->
            val separator = if (index == 0) "?" else "&"
            acc + separator + "${pair.first}=${pair.second}"
        }
    }

    /**
     * Notifies that the downstream consumers of SignalClient are ready to consume messages.
     * Until this method is called, any messages received through the websocket are buffered.
     *
     * Should be called after resolving the join message.
     */
    fun onReadyForResponses() {
        if (responseFlowJob != null) {
            return
        }
        synchronized(responseFlowJobLock) {
            if (responseFlowJob == null) {
                responseFlowJob = coroutineScope.launch {
                    responseFlow.collect { (ws, response) ->
                        responseFlow.resetReplayCache()
                        handleSignalResponseImpl(ws, response)
                    }
                }
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

    // --------------------------------- WebSocket Listener --------------------------------------//
    override fun onMessage(webSocket: WebSocket, text: String) {
        if (webSocket != currentWs) {
            // Possibly message from old websocket, discard.
            return
        }

        LKLog.w { "received JSON message, unsupported in this version." }
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        if (webSocket != currentWs) {
            // Possibly message from old websocket, discard.
            return
        }
        val byteArray = bytes.toByteArray()
        val signalResponseBuilder = LivekitRtc.SignalResponse.newBuilder()
            .mergeFrom(byteArray)
        val response = signalResponseBuilder.build()

        handleSignalResponse(webSocket, response)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        if (webSocket != currentWs) {
            return
        }
        handleWebSocketClose(reason, code)
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        LKLog.v { "websocket closing" }
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        if (webSocket != currentWs) {
            return
        }
        var reason: String? = null
        try {
            lastUrl?.let {
                val validationUrl = it.toHttpUrl().replaceFirst("/rtc?", "/rtc/validate?")
                val request = Request.Builder().url(validationUrl).build()
                val resp = okHttpClient.newCall(request).execute()
                val body = resp.body
                if (!resp.isSuccessful) {
                    reason = body?.string()
                }
                body?.close()
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

        if (wasConnected) {
            // onClosing/onClosed will not be called after onFailure.
            // Handle websocket closure here.
            handleWebSocketClose(
                reason = reason ?: response?.toString() ?: t.localizedMessage ?: "websocket failure",
                code = response?.code ?: CLOSE_REASON_WEBSOCKET_FAILURE,
            )
        }
    }

    private fun handleWebSocketClose(reason: String, code: Int) {
        LKLog.v { "websocket closed" }
        isConnected = false
        listener?.onClose(reason, code)
        requestFlow.resetReplayCache()
        responseFlow.resetReplayCache()
        pingJob?.cancel()
        pongJob?.cancel()
    }

    // ------------------------------- End WebSocket Listener ------------------------------------//

    private fun fromProtoSessionDescription(sd: LivekitRtc.SessionDescription): SessionDescription {
        val rtcSdpType = when (sd.type) {
            SD_TYPE_ANSWER -> SessionDescription.Type.ANSWER
            SD_TYPE_OFFER -> SessionDescription.Type.OFFER
            SD_TYPE_PRANSWER -> SessionDescription.Type.PRANSWER
            SD_TYPE_ROLLBACK -> SessionDescription.Type.ROLLBACK
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
            sdpMLineIndex = candidate.sdpMLineIndex,
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
        stream: String?,
        builder: LivekitRtc.AddTrackRequest.Builder = LivekitRtc.AddTrackRequest.newBuilder(),
    ) {
        val encryptionType = lastRoomOptions?.e2eeOptions?.encryptionType ?: LivekitModels.Encryption.Type.NONE
        val addTrackRequest = builder.apply {
            setCid(cid)
            setName(name)
            setType(type)
            if (stream != null) {
                setStream(stream)
            } else {
                clearStream()
            }
            encryption = encryptionType
        }
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
        fps: Int?,
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

                if (fps != null) {
                    setFps(fps)
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
        participantTrackPermissions: List<ParticipantTrackPermission>,
    ) {
        val update = LivekitRtc.SubscriptionPermission.newBuilder()
            .setAllParticipants(allParticipants)
            .addAllTrackPermissions(participantTrackPermissions.map { it.toProto() })

        val request = LivekitRtc.SignalRequest.newBuilder()
            .setSubscriptionPermission(update)
            .build()

        sendRequest(request)
    }

    fun sendUpdateLocalMetadata(metadata: String?, name: String?, attributes: Map<String, String>? = emptyMap()) {
        val update = LivekitRtc.UpdateParticipantMetadata.newBuilder()
            .setMetadata(metadata ?: "")
            .setName(name ?: "")
            .putAllAttributes(attributes)

        val request = LivekitRtc.SignalRequest.newBuilder()
            .setUpdateMetadata(update)
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
        val request = with(LivekitRtc.SignalRequest.newBuilder()) {
            leave = with(LivekitRtc.LeaveRequest.newBuilder()) {
                reason = LivekitModels.DisconnectReason.CLIENT_INITIATED
                // server doesn't process this field, keeping it here to indicate the intent of a full disconnect
                action = LivekitRtc.LeaveRequest.Action.DISCONNECT
                build()
            }
            build()
        }

        sendRequest(request)
    }

    fun sendPing(): Long {
        val time = Date().time
        sendRequest(
            with(LivekitRtc.SignalRequest.newBuilder()) {
                ping = time
                build()
            },
        )
        sendRequest(
            with(LivekitRtc.SignalRequest.newBuilder()) {
                pingReq = with(LivekitRtc.Ping.newBuilder()) {
                    rtt = this@SignalClient.rtt
                    timestamp = time
                    build()
                }
                build()
            },
        )

        return time
    }

    fun sendUpdateLocalAudioTrack(trackSid: String, features: Collection<AudioTrackFeature>) {
        val request = with(LivekitRtc.SignalRequest.newBuilder()) {
            updateAudioTrack = with(LivekitRtc.UpdateLocalAudioTrack.newBuilder()) {
                setTrackSid(trackSid)
                addAllFeatures(features)
                build()
            }
            build()
        }

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

    private fun handleSignalResponse(ws: WebSocket, response: LivekitRtc.SignalResponse) {
        if (ws != currentWs) {
            return
        }

        LKLog.v { "response: $response" }

        if (!isConnected) {
            var shouldProcessMessage = false

            // Only handle certain messages if not connected.
            if (response.hasJoin()) {
                isConnected = true
                startRequestQueue()
                pingTimeoutDurationMillis = response.join.pingTimeout.toLong() * 1000
                pingIntervalDurationMillis = response.join.pingInterval.toLong() * 1000
                startPingJob()
                try {
                    serverVersion = Semver(response.join.serverVersion)
                } catch (t: Throwable) {
                    LKLog.w(t) { "Thrown while trying to parse server version." }
                }
                joinContinuation?.resumeWith(Result.success(Either.Left(response.join)))
            } else if (response.hasLeave()) {
                // Some reconnects may immediately send leave back without a join response first.
                handleSignalResponseImpl(ws, response)
            } else if (isReconnecting) {
                // When reconnecting, any message received means signal reconnected.
                // Newer servers will send a reconnect response first
                isReconnecting = false
                isConnected = true

                // Restart ping job with old settings.
                startPingJob()

                if (response.hasReconnect()) {
                    joinContinuation?.resumeWith(Result.success(Either.Right(Either.Left(response.reconnect))))
                } else {
                    joinContinuation?.resumeWith(Result.success(Either.Right(Either.Right(Unit))))
                    // Non-reconnect response, handle normally
                    shouldProcessMessage = true
                }
            } else {
                LKLog.e { "Received response while not connected. $response" }
            }
            if (!shouldProcessMessage) {
                return
            }
        }
        responseFlow.tryEmit(ws to response)
    }

    private fun handleSignalResponseImpl(ws: WebSocket, response: LivekitRtc.SignalResponse) {
        if (ws != currentWs) {
            LKLog.v { "received message from old websocket, discarding." }
            return
        }

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
                    iceCandidateJson.candidate,
                )
                listener?.onTrickle(iceCandidate, response.trickle.target)
            }

            LivekitRtc.SignalResponse.MessageCase.UPDATE -> {
                listener?.onParticipantUpdate(response.update.participantsList)
            }

            LivekitRtc.SignalResponse.MessageCase.TRACK_SUBSCRIBED -> {
                listener?.onLocalTrackSubscribed(response.trackSubscribed)
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
                if ((serverVersion?.compareTo(versionToIgnoreUpTo) ?: 1) <= 0) {
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

            LivekitRtc.SignalResponse.MessageCase.PONG -> {
                resetPingTimeout()
            }

            LivekitRtc.SignalResponse.MessageCase.PONG_RESP -> {
                rtt = Date().time - response.pongResp.lastPingTimestamp
                resetPingTimeout()
            }

            LivekitRtc.SignalResponse.MessageCase.RECONNECT -> {
                // TODO
            }

            LivekitRtc.SignalResponse.MessageCase.SUBSCRIPTION_RESPONSE -> {
                // TODO
            }

            LivekitRtc.SignalResponse.MessageCase.REQUEST_RESPONSE -> {
                // TODO
            }

            LivekitRtc.SignalResponse.MessageCase.MESSAGE_NOT_SET,
            null,
            -> {
                LKLog.v { "empty messageCase!" }
            }
        }
    }

    private fun startPingJob() {
        if (pingJob == null && pingIntervalDurationMillis != 0L) {
            pingJob = coroutineScope.launch {
                while (true) {
                    delay(pingIntervalDurationMillis)
                    val pingTimestamp = sendPing()
                    startPingTimeout(pingTimestamp)
                }
            }
        }
    }

    private fun startPingTimeout(timestamp: Long) {
        if (pongJob != null) {
            return
        }
        pongJob = coroutineScope.launch {
            delay(pingTimeoutDurationMillis)
            LKLog.d { "Ping timeout reached for ping sent at $timestamp." }
            currentWs?.close(CLOSE_REASON_PING_TIMEOUT, "Ping timeout")
        }
    }

    private fun resetPingTimeout() {
        pongJob?.cancel()
        pongJob = null
    }

    /**
     * Closes out any existing websocket connection, and cleans up used resources.
     *
     * Can be reused afterwards.
     */
    fun close(code: Int = CLOSE_REASON_NORMAL_CLOSURE, reason: String = "Normal Closure", shouldClearQueuedRequests: Boolean = true) {
        LKLog.v(Exception()) { "Closing SignalClient: code = $code, reason = $reason" }
        isConnected = false
        isReconnecting = false
        if (::coroutineScope.isInitialized) {
            coroutineScope.close()
        }
        requestFlowJob?.cancel()
        requestFlowJob = null
        responseFlowJob?.cancel()
        responseFlowJob = null
        pingJob?.cancel()
        pingJob = null
        pongJob?.cancel()
        pongJob = null
        currentWs?.close(code, reason)
        currentWs = null
        joinContinuation?.cancel()
        joinContinuation = null
        if (shouldClearQueuedRequests) {
            requestFlow.resetReplayCache()
        }
        responseFlow.resetReplayCache()
        lastUrl = null
        lastOptions = null
        lastRoomOptions = null
        serverVersion = null
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
        fun onLocalTrackSubscribed(trackSubscribed: LivekitRtc.TrackSubscribed)
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
        const val CONNECT_QUERY_NETWORK_TYPE = "network"
        const val CONNECT_QUERY_PARTICIPANT_SID = "sid"

        const val SD_TYPE_ANSWER = "answer"
        const val SD_TYPE_OFFER = "offer"
        const val SD_TYPE_PRANSWER = "pranswer"
        const val SD_TYPE_ROLLBACK = "rollback"
        const val SDK_TYPE = "android"

        private val skipQueueTypes = listOf(
            LivekitRtc.SignalRequest.MessageCase.SYNC_STATE,
            LivekitRtc.SignalRequest.MessageCase.TRICKLE,
            LivekitRtc.SignalRequest.MessageCase.OFFER,
            LivekitRtc.SignalRequest.MessageCase.ANSWER,
            LivekitRtc.SignalRequest.MessageCase.SIMULATE,
            LivekitRtc.SignalRequest.MessageCase.LEAVE,
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
        const val CLOSE_REASON_NORMAL_CLOSURE = 1000
        const val CLOSE_REASON_PING_TIMEOUT = 3000
        const val CLOSE_REASON_WEBSOCKET_FAILURE = 3500
    }
}

@Suppress("EnumEntryName", "unused")
enum class ProtocolVersion(val value: Int) {
    v1(1),
    v2(2),
    v3(3),
    v4(4),
    v5(5),
    v6(6),
    v7(7),
    v8(8),
    v9(9),
    v10(10),
    v11(11),
    v12(12),

    // new leave request handling
    v13(13),
}
