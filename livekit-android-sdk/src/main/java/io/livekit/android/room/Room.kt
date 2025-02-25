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

@file:Suppress("unused")

package io.livekit.android.room

import android.content.Context
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import androidx.annotation.VisibleForTesting
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import io.livekit.android.ConnectOptions
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.RoomOptions
import io.livekit.android.Version
import io.livekit.android.audio.AudioHandler
import io.livekit.android.audio.AudioProcessingController
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.audio.AuthedAudioProcessingController
import io.livekit.android.audio.CommunicationWorkaround
import io.livekit.android.dagger.InjectionNames
import io.livekit.android.e2ee.E2EEManager
import io.livekit.android.e2ee.E2EEOptions
import io.livekit.android.events.*
import io.livekit.android.memory.CloseableManager
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.metrics.collectMetrics
import io.livekit.android.room.network.NetworkCallbackManagerFactory
import io.livekit.android.room.participant.*
import io.livekit.android.room.provisions.LKObjects
import io.livekit.android.room.track.*
import io.livekit.android.room.types.toSDKType
import io.livekit.android.room.util.ConnectionWarmer
import io.livekit.android.util.FlowObservable
import io.livekit.android.util.LKLog
import io.livekit.android.util.flow
import io.livekit.android.util.flowDelegate
import io.livekit.android.util.invoke
import io.livekit.android.webrtc.getFilteredStats
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import livekit.LivekitModels
import livekit.LivekitRtc
import livekit.org.webrtc.*
import livekit.org.webrtc.audio.AudioDeviceModule
import java.net.URI
import java.util.Date
import javax.inject.Named

class Room
@AssistedInject
constructor(
    @Assisted private val context: Context,
    private val engine: RTCEngine,
    private val eglBase: EglBase,
    localParticipantFactory: LocalParticipant.Factory,
    private val defaultsManager: DefaultsManager,
    @Named(InjectionNames.DISPATCHER_DEFAULT)
    private val defaultDispatcher: CoroutineDispatcher,
    @Named(InjectionNames.DISPATCHER_IO)
    private val ioDispatcher: CoroutineDispatcher,
    /**
     * The [AudioHandler] for setting up the audio as need.
     *
     * By default, this is an instance of [AudioSwitchHandler].
     *
     * This can be substituted for your own custom implementation through
     * [LiveKitOverrides.audioOptions] when creating the room with [LiveKit.create].
     *
     * @see [audioSwitchHandler]
     * @see [AudioSwitchHandler]
     */
    val audioHandler: AudioHandler,
    private val closeableManager: CloseableManager,
    private val e2EEManagerFactory: E2EEManager.Factory,
    private val communicationWorkaround: CommunicationWorkaround,
    val audioProcessingController: AudioProcessingController,
    /**
     * A holder for objects that are used internally within LiveKit.
     */
    val lkObjects: LKObjects,
    networkCallbackManagerFactory: NetworkCallbackManagerFactory,
    private val audioDeviceModule: AudioDeviceModule,
    private val regionUrlProviderFactory: RegionUrlProvider.Factory,
    private val connectionWarmer: ConnectionWarmer,
) : RTCEngine.Listener, ParticipantListener {

    private lateinit var coroutineScope: CoroutineScope
    private val eventBus = BroadcastEventBus<RoomEvent>()
    val events = eventBus.readOnly()

    init {
        engine.listener = this
    }

    enum class State {
        CONNECTING,
        CONNECTED,
        DISCONNECTED,
        RECONNECTING,
    }

    /**
     * @suppress
     */
    enum class SimulateScenario {
        SPEAKER_UPDATE,
        NODE_FAILURE,
        MIGRATION,
        SERVER_LEAVE,
        SERVER_LEAVE_FULL_RECONNECT,
    }

    @Serializable
    @JvmInline
    value class Sid(val sid: String)

    /**
     * The session id of the room.
     *
     * Note: the sid may not be populated immediately upon [connect],
     * so using the suspend function [getSid] or listening to the flow
     * `room::sid.flow` is highly advised.
     */
    @FlowObservable
    @get:FlowObservable
    var sid: Sid? by flowDelegate(null)
        private set

    /**
     * Gets the sid of the room.
     *
     * If the sid is not yet available, will suspend until received.
     */
    suspend fun getSid(): Sid {
        return this@Room::sid.flow
            .filterNotNull()
            .first()
    }

    @FlowObservable
    @get:FlowObservable
    var name: String? by flowDelegate(null)
        private set

    @FlowObservable
    @get:FlowObservable
    var state: State by flowDelegate(State.DISCONNECTED) { new, old ->
        if (new != old) {
            when (new) {
                State.CONNECTING -> {
                    audioHandler.start()
                    communicationWorkaround.start()
                }

                State.DISCONNECTED -> {
                    audioHandler.stop()
                    communicationWorkaround.stop()
                }

                else -> {}
            }
        }
    }
        private set

    @FlowObservable
    @get:FlowObservable
    var metadata: String? by flowDelegate(null)
        private set

    @FlowObservable
    @get:FlowObservable
    var isRecording: Boolean by flowDelegate(false)
        private set

    /**
     * @suppress
     */
    @VisibleForTesting
    var enableMetrics: Boolean = true

    /**
     *  end-to-end encryption manager
     */
    var e2eeManager: E2EEManager? = null

    /**
     * Automatically manage quality of subscribed video tracks, subscribe to the
     * an appropriate resolution based on the size of the video elements that tracks
     * are attached to.
     *
     * Also observes the visibility of attached tracks and pauses receiving data
     * if they are not visible.
     *
     * Defaults to false.
     */
    var adaptiveStream: Boolean = false

    /**
     *  audio processing is enabled
     */
    var audioProcessorIsEnabled: Boolean = false

    /**
     * Dynamically pauses video layers that are not being consumed by any subscribers,
     * significantly reducing publishing CPU and bandwidth usage.
     *
     * Defaults to false.
     *
     * Will be enabled if SVC codecs (i.e. VP9/AV1) are used. Multi-codec simulcast
     * requires dynacast.
     */
    var dynacast: Boolean
        get() = localParticipant.dynacast
        set(value) {
            localParticipant.dynacast = value
        }

    /**
     * Options for end-to-end encryption. Must be setup prior to [connect].
     *
     * If null, e2ee will be disabled.
     */
    var e2eeOptions: E2EEOptions? = null

    /**
     * Default options to use when creating an audio track.
     */
    var audioTrackCaptureDefaults: LocalAudioTrackOptions by defaultsManager::audioTrackCaptureDefaults

    /**
     * Default options to use when publishing an audio track.
     */
    var audioTrackPublishDefaults: AudioTrackPublishDefaults by defaultsManager::audioTrackPublishDefaults

    /**
     * Default options to use when creating a video track.
     */
    var videoTrackCaptureDefaults: LocalVideoTrackOptions by defaultsManager::videoTrackCaptureDefaults

    /**
     * Default options to use when publishing a video track.
     */
    var videoTrackPublishDefaults: VideoTrackPublishDefaults by defaultsManager::videoTrackPublishDefaults

    /**
     * Default options to use when creating a screen share track.
     */
    var screenShareTrackCaptureDefaults: LocalVideoTrackOptions by defaultsManager::screenShareTrackCaptureDefaults

    /**
     * Default options to use when publishing a screen share track.
     */
    var screenShareTrackPublishDefaults: VideoTrackPublishDefaults by defaultsManager::screenShareTrackPublishDefaults

    val localParticipant: LocalParticipant = localParticipantFactory.create(dynacast = false).apply {
        internalListener = this@Room
    }

    private var mutableRemoteParticipants by flowDelegate(emptyMap<Participant.Identity, RemoteParticipant>())

    @FlowObservable
    @get:FlowObservable
    val remoteParticipants: Map<Participant.Identity, RemoteParticipant>
        get() = mutableRemoteParticipants

    /**
     * A convenience getter for the audio handler as a [AudioSwitchHandler].
     *
     * Will return null if [audioHandler] is not a [AudioSwitchHandler].
     */
    val audioSwitchHandler: AudioSwitchHandler?
        get() = audioHandler as? AudioSwitchHandler

    private var sidToIdentity = mutableMapOf<Participant.Sid, Participant.Identity>()

    private var mutableActiveSpeakers by flowDelegate(emptyList<Participant>())

    @FlowObservable
    @get:FlowObservable
    val activeSpeakers: List<Participant>
        get() = mutableActiveSpeakers

    private var hasLostConnectivity: Boolean = false
    private var connectOptions: ConnectOptions = ConnectOptions()

    private var stateLock = Mutex()

    private var regionUrlProvider: RegionUrlProvider? = null
    private var regionUrl: String? = null

    private var transcriptionReceivedTimes = mutableMapOf<String, Long>()

    private fun getCurrentRoomOptions(): RoomOptions =
        RoomOptions(
            adaptiveStream = adaptiveStream,
            dynacast = dynacast,
            e2eeOptions = e2eeOptions,
            audioTrackCaptureDefaults = audioTrackCaptureDefaults,
            videoTrackCaptureDefaults = videoTrackCaptureDefaults,
            audioTrackPublishDefaults = audioTrackPublishDefaults,
            videoTrackPublishDefaults = videoTrackPublishDefaults,
            screenShareTrackCaptureDefaults = screenShareTrackCaptureDefaults,
            screenShareTrackPublishDefaults = screenShareTrackPublishDefaults,
        )

    /**
     * prepareConnection should be called as soon as the page is loaded, in order
     * to speed up the connection attempt. This function will
     * - perform DNS resolution and pre-warm the DNS cache
     * - establish TLS connection and cache TLS keys
     *
     * With LiveKit Cloud, it will also determine the best edge data center for
     * the current client to connect to if a token is provided.
     */
    suspend fun prepareConnection(url: String, token: String? = null) {
        if (state != State.DISCONNECTED) {
            LKLog.i { "Room is not in disconnected state, ignoring prepareConnection call." }
            return
        }
        LKLog.d { "preparing connection to $url" }

        try {
            val urlActual = URI(url)
            if (urlActual.isLKCloud() && token != null) {
                val regionUrlProvider = regionUrlProviderFactory.create(urlActual, token)
                this.regionUrlProvider = regionUrlProvider

                val regionUrl = regionUrlProvider.getNextBestRegionUrl()
                // we will not replace the regionUrl if an attempt had already started
                // to avoid overriding regionUrl after a new connection attempt had started
                if (regionUrl != null && state == State.DISCONNECTED) {
                    this.regionUrl = regionUrl
                    connectionWarmer.fetch(regionUrl)
                    LKLog.d { "prepared connection to $regionUrl" }
                }
            } else {
                connectionWarmer.fetch(url)
            }
        } catch (e: Exception) {
            LKLog.e(e) { "Error while preparing connection:" }
        }
    }

    /**
     * Connect to a LiveKit Room.
     *
     * @param url
     * @param token
     * @param options
     *
     * @throws IllegalStateException when connect is attempted while the room is not disconnected.
     * @throws Exception when connection fails
     */
    @Throws(Exception::class)
    suspend fun connect(url: String, token: String, options: ConnectOptions = ConnectOptions()) = coroutineScope {
        if (state != State.DISCONNECTED) {
            throw IllegalStateException("Room.connect attempted while room is not disconnected!")
        }
        val roomOptions: RoomOptions
        stateLock.withLock {
            if (state != State.DISCONNECTED) {
                throw IllegalStateException("Room.connect attempted while room is not disconnected!")
            }
            if (::coroutineScope.isInitialized) {
                val job = coroutineScope.coroutineContext.job
                coroutineScope.cancel()
                job.join()
            }

            state = State.CONNECTING
            connectOptions = options

            coroutineScope = CoroutineScope(defaultDispatcher + SupervisorJob())

            roomOptions = getCurrentRoomOptions()

            // Setup local participant.
            localParticipant.reinitialize()
            setupLocalParticipantEventHandling()

            if (roomOptions.e2eeOptions != null) {
                e2eeManager = e2EEManagerFactory.create(roomOptions.e2eeOptions.keyProvider).apply {
                    setup(this@Room) { event ->
                        coroutineScope.launch {
                            emitWhenConnected(event)
                        }
                    }
                }
            }
        }

        // Use an empty coroutineExceptionHandler since we want to
        // rethrow all throwables from the connect job.
        val emptyCoroutineExceptionHandler = CoroutineExceptionHandler { _, _ -> }
        val connectJob = coroutineScope.launch(
            ioDispatcher + emptyCoroutineExceptionHandler,
        ) {
            if (audioProcessingController is AuthedAudioProcessingController) {
                audioProcessingController.authenticate(url, token)
            }

            // Don't use URL equals.
            if (regionUrlProvider?.serverUrl.toString() != url) {
                regionUrl = null
                regionUrlProvider = null
            }

            val urlObj = URI(url)
            if (urlObj.isLKCloud()) {
                if (regionUrlProvider == null) {
                    regionUrlProvider = regionUrlProviderFactory.create(urlObj, token)
                } else {
                    regionUrlProvider?.token = token
                }

                // trigger the first fetch without waiting for a response
                // if initial connection fails, this will speed up picking regional url
                // on subsequent runs
                launch {
                    try {
                        regionUrlProvider?.fetchRegionSettings()
                    } catch (e: Exception) {
                        LKLog.w(e) { "could not fetch region settings" }
                    }
                }
            }

            var nextUrl: String? = regionUrl ?: url
            regionUrl = null

            while (nextUrl != null) {
                val connectUrl = nextUrl
                nextUrl = null
                try {
                    engine.regionUrlProvider = regionUrlProvider
                    engine.join(connectUrl, token, options, roomOptions)
                } catch (e: Exception) {
                    if (e is CancellationException) {
                        throw e // rethrow to properly cancel.
                    }

                    nextUrl = regionUrlProvider?.getNextBestRegionUrl()
                    if (nextUrl != null) {
                        LKLog.d(e) { "Connection to $connectUrl failed, retrying with another region: $nextUrl" }
                    } else {
                        throw e // rethrow since no more regions to try.
                    }
                }
            }

            ensureActive()
            networkCallbackManager.registerCallback()
            if (options.audio) {
                val audioTrack = localParticipant.createAudioTrack()
                localParticipant.publishAudioTrack(audioTrack)
            }
            ensureActive()
            if (options.video) {
                val videoTrack = localParticipant.createVideoTrack()
                localParticipant.publishVideoTrack(videoTrack)
            }

            coroutineScope.launch {
                if (enableMetrics) {
                    collectMetrics(room = this@Room, rtcEngine = engine)
                }
            }
        }

        val outerHandler = coroutineContext.job.invokeOnCompletion { cause ->
            // Cancel connect job if invoking coroutine is cancelled.
            if (cause is CancellationException) {
                connectJob.cancel(cause)
            }
        }

        var error: Throwable? = null
        connectJob.invokeOnCompletion { cause ->
            outerHandler.dispose()
            error = cause
        }
        connectJob.join()

        error?.let { throw it }
    }

    /**
     * Disconnect from the room.
     */
    fun disconnect() {
        engine.client.sendLeave()
        handleDisconnect(DisconnectReason.CLIENT_INITIATED)
    }

    /**
     * Copies all the options to the Room object.
     *
     * Any null values in [options] will not overwrite existing values.
     * To clear existing values on the Room object, explicitly set the value
     * directly instead of using this method.
     */
    fun setRoomOptions(options: RoomOptions) {
        options.audioTrackCaptureDefaults?.let {
            audioTrackCaptureDefaults = it
        }
        options.videoTrackCaptureDefaults?.let {
            videoTrackCaptureDefaults = it
        }

        options.audioTrackPublishDefaults?.let {
            audioTrackPublishDefaults = it
        }
        options.videoTrackPublishDefaults?.let {
            videoTrackPublishDefaults = it
        }
        options.screenShareTrackCaptureDefaults?.let {
            screenShareTrackCaptureDefaults = it
        }
        options.screenShareTrackPublishDefaults?.let {
            screenShareTrackPublishDefaults = it
        }
        adaptiveStream = options.adaptiveStream
        dynacast = options.dynacast
        e2eeOptions = options.e2eeOptions
    }

    /**
     * Release all resources held by this object.
     *
     * Once called, this room object must not be used to connect to a server and a new one
     * must be created.
     */
    fun release() {
        closeableManager.close()
    }

    /**
     * @suppress
     */
    override fun onJoinResponse(response: LivekitRtc.JoinResponse) {
        LKLog.i { "Connected to server, server version: ${response.serverVersion}, client version: ${Version.CLIENT_VERSION}" }

        if (response.room.sid != null) {
            sid = Sid(response.room.sid)
        } else {
            sid = null
        }
        name = response.room.name
        metadata = response.room.metadata

        if (e2eeManager != null && !response.sifTrailer.isEmpty) {
            e2eeManager!!.keyProvider().setSifTrailer(response.sifTrailer.toByteArray())
        }

        if (response.room.activeRecording != isRecording) {
            isRecording = response.room.activeRecording
            eventBus.postEvent(RoomEvent.RecordingStatusChanged(this, isRecording), coroutineScope)
        }

        if (!response.hasParticipant()) {
            throw RoomException.ConnectException("server didn't return a local participant")
        }

        localParticipant.updateFromInfo(response.participant)
        localParticipant.setEnabledPublishCodecs(response.enabledPublishCodecsList)

        if (response.otherParticipantsList.isNotEmpty()) {
            response.otherParticipantsList.forEach { info ->
                getOrCreateRemoteParticipant(Participant.Identity(info.identity), info)
            }
        }
    }

    private fun setupLocalParticipantEventHandling() {
        coroutineScope.launch {
            localParticipant.events.collect {
                when (it) {
                    is ParticipantEvent.TrackPublished -> emitWhenConnected(
                        RoomEvent.TrackPublished(
                            room = this@Room,
                            publication = it.publication,
                            participant = it.participant,
                        ),
                    )

                    is ParticipantEvent.TrackUnpublished -> emitWhenConnected(
                        RoomEvent.TrackUnpublished(
                            room = this@Room,
                            publication = it.publication,
                            participant = it.participant,
                        ),
                    )

                    is ParticipantEvent.ParticipantPermissionsChanged -> emitWhenConnected(
                        RoomEvent.ParticipantPermissionsChanged(
                            room = this@Room,
                            participant = it.participant,
                            newPermissions = it.newPermissions,
                            oldPermissions = it.oldPermissions,
                        ),
                    )

                    is ParticipantEvent.MetadataChanged -> {
                        emitWhenConnected(
                            RoomEvent.ParticipantMetadataChanged(
                                this@Room,
                                it.participant,
                                it.prevMetadata,
                            ),
                        )
                    }

                    is ParticipantEvent.AttributesChanged -> {
                        emitWhenConnected(
                            RoomEvent.ParticipantAttributesChanged(
                                this@Room,
                                it.participant,
                                it.changedAttributes,
                                it.oldAttributes,
                            ),
                        )
                    }

                    is ParticipantEvent.NameChanged -> {
                        emitWhenConnected(
                            RoomEvent.ParticipantNameChanged(
                                this@Room,
                                it.participant,
                                it.name,
                            ),
                        )
                    }

                    else -> {
                        // do nothing
                    }
                }
            }
        }
    }

    private fun handleParticipantDisconnect(identity: Participant.Identity) {
        val newParticipants = mutableRemoteParticipants.toMutableMap()
        val removedParticipant = newParticipants.remove(identity) ?: return
        removedParticipant.trackPublications.values.toList().forEach { publication ->
            removedParticipant.unpublishTrack(publication.sid, true)
        }

        mutableRemoteParticipants = newParticipants
        eventBus.postEvent(RoomEvent.ParticipantDisconnected(this, removedParticipant), coroutineScope)

        localParticipant.handleParticipantDisconnect(identity)
    }

    fun getParticipantBySid(sid: String): Participant? {
        return getParticipantBySid(Participant.Sid(sid))
    }

    fun getParticipantBySid(sid: Participant.Sid): Participant? {
        if (sid == localParticipant.sid) {
            return localParticipant
        } else {
            return remoteParticipants[sidToIdentity[sid]]
        }
    }

    fun getParticipantByIdentity(identity: String): Participant? {
        return getParticipantByIdentity(Participant.Identity(identity))
    }

    fun getParticipantByIdentity(identity: Participant.Identity): Participant? {
        if (identity == localParticipant.identity) {
            return localParticipant
        } else {
            return remoteParticipants[identity]
        }
    }

    @Synchronized
    private fun getOrCreateRemoteParticipant(
        identity: Participant.Identity,
        info: LivekitModels.ParticipantInfo,
    ): RemoteParticipant {
        var participant = remoteParticipants[identity]
        if (participant != null) {
            return participant
        }

        participant = RemoteParticipant(info, engine.client, ioDispatcher, defaultDispatcher)
        participant.internalListener = this

        coroutineScope.launch {
            participant.events.collect {
                when (it) {
                    is ParticipantEvent.TrackPublished -> {
                        if (state == State.CONNECTED) {
                            eventBus.postEvent(
                                RoomEvent.TrackPublished(
                                    room = this@Room,
                                    publication = it.publication,
                                    participant = it.participant,
                                ),
                            )
                        }
                    }

                    is ParticipantEvent.TrackStreamStateChanged -> eventBus.postEvent(
                        RoomEvent.TrackStreamStateChanged(
                            this@Room,
                            it.trackPublication,
                            it.streamState,
                        ),
                    )

                    is ParticipantEvent.TrackSubscriptionPermissionChanged -> eventBus.postEvent(
                        RoomEvent.TrackSubscriptionPermissionChanged(
                            this@Room,
                            it.participant,
                            it.trackPublication,
                            it.subscriptionAllowed,
                        ),
                    )

                    is ParticipantEvent.MetadataChanged -> {
                        emitWhenConnected(
                            RoomEvent.ParticipantMetadataChanged(
                                this@Room,
                                it.participant,
                                it.prevMetadata,
                            ),
                        )
                    }

                    is ParticipantEvent.AttributesChanged -> {
                        emitWhenConnected(
                            RoomEvent.ParticipantAttributesChanged(
                                this@Room,
                                it.participant,
                                it.changedAttributes,
                                it.oldAttributes,
                            ),
                        )
                    }

                    is ParticipantEvent.NameChanged -> {
                        emitWhenConnected(
                            RoomEvent.ParticipantNameChanged(
                                this@Room,
                                it.participant,
                                it.name,
                            ),
                        )
                    }

                    is ParticipantEvent.ParticipantPermissionsChanged -> eventBus.postEvent(
                        RoomEvent.ParticipantPermissionsChanged(
                            room = this@Room,
                            participant = it.participant,
                            newPermissions = it.newPermissions,
                            oldPermissions = it.oldPermissions,
                        ),
                    )

                    else -> {
                        // do nothing
                    }
                }
            }
        }

        participant.updateFromInfo(info)

        val newRemoteParticipants = mutableRemoteParticipants.toMutableMap()
        newRemoteParticipants[identity] = participant
        mutableRemoteParticipants = newRemoteParticipants
        sidToIdentity[participant.sid] = identity

        return participant
    }

    private fun handleActiveSpeakersUpdate(speakerInfos: List<LivekitModels.SpeakerInfo>) {
        val speakers = mutableListOf<Participant>()
        val seenSids = mutableSetOf<Participant.Sid>()
        val localParticipant = localParticipant
        speakerInfos.forEach { speakerInfo ->
            val speakerSid = Participant.Sid(speakerInfo.sid)
            seenSids.add(speakerSid)

            val participant = getParticipantBySid(speakerSid) ?: return@forEach
            participant.audioLevel = speakerInfo.level
            participant.isSpeaking = true
            speakers.add(participant)
        }

        if (!seenSids.contains(localParticipant.sid)) {
            localParticipant.audioLevel = 0.0f
            localParticipant.isSpeaking = false
        }
        remoteParticipants.values
            .filterNot { seenSids.contains(it.sid) }
            .forEach {
                it.audioLevel = 0.0f
                it.isSpeaking = false
            }

        mutableActiveSpeakers = speakers.toList()
        eventBus.postEvent(RoomEvent.ActiveSpeakersChanged(this, mutableActiveSpeakers), coroutineScope)
    }

    private fun handleSpeakersChanged(speakerInfos: List<LivekitModels.SpeakerInfo>) {
        val updatedSpeakers = mutableMapOf<Participant.Sid, Participant>()
        activeSpeakers.forEach { participant ->
            updatedSpeakers[participant.sid] = participant
        }

        speakerInfos.forEach { speaker ->
            val speakerSid = Participant.Sid(speaker.sid)
            val participant = getParticipantBySid(speakerSid) ?: return@forEach

            participant.audioLevel = speaker.level
            participant.isSpeaking = speaker.active

            if (speaker.active) {
                updatedSpeakers[speakerSid] = participant
            } else {
                updatedSpeakers.remove(speakerSid)
            }
        }

        val updatedSpeakersList = updatedSpeakers.values.toList()
            .sortedBy { it.audioLevel }

        mutableActiveSpeakers = updatedSpeakersList.toList()
        eventBus.postEvent(RoomEvent.ActiveSpeakersChanged(this, mutableActiveSpeakers), coroutineScope)
    }

    private fun reconnect() {
        if (state == State.RECONNECTING) {
            return
        }
        engine.reconnect()
    }

    private fun handleDisconnect(reason: DisconnectReason) {
        if (state == State.DISCONNECTED) {
            return
        }
        runBlocking {
            stateLock.withLock {
                if (state == State.DISCONNECTED) {
                    return@runBlocking
                }
                networkCallbackManager.unregisterCallback()

                state = State.DISCONNECTED
                cleanupRoom()
                engine.close()

                localParticipant.dispose()

                // Ensure all observers see the disconnected before closing scope.
                eventBus.postEvent(RoomEvent.Disconnected(this@Room, null, reason), coroutineScope).join()
                coroutineScope.cancel()
            }
        }
    }

    /**
     * Removes all participants and tracks from the room.
     */
    private fun cleanupRoom() {
        e2eeManager?.cleanUp()
        e2eeManager = null
        localParticipant.cleanup()
        remoteParticipants.keys.toMutableSet() // copy keys to avoid concurrent modifications.
            .forEach { sid -> handleParticipantDisconnect(sid) }

        sid = null
        metadata = null
        name = null
        isRecording = false
        sidToIdentity.clear()
    }

    private fun sendSyncState() {
        // Whether we're sending subscribed tracks or tracks to unsubscribe.
        val sendUnsub = connectOptions.autoSubscribe
        val participantTracksList = mutableListOf<LivekitModels.ParticipantTracks>()
        for (participant in remoteParticipants.values) {
            val builder = LivekitModels.ParticipantTracks.newBuilder()
            builder.participantSid = participant.sid.value
            for (trackPub in participant.trackPublications.values) {
                val remoteTrackPub = (trackPub as? RemoteTrackPublication) ?: continue
                if (remoteTrackPub.subscribed != sendUnsub) {
                    builder.addTrackSids(remoteTrackPub.sid)
                }
            }

            if (builder.trackSidsCount > 0) {
                participantTracksList.add(builder.build())
            }
        }

        // backwards compatibility for protocol version < 6
        val trackSids = participantTracksList.map { it.trackSidsList }
            .flatten()

        val subscription = LivekitRtc.UpdateSubscription.newBuilder()
            .setSubscribe(!sendUnsub)
            .addAllParticipantTracks(participantTracksList)
            .addAllTrackSids(trackSids)
            .build()
        val publishedTracks = localParticipant.publishTracksInfo()
        engine.sendSyncState(subscription, publishedTracks)
    }

    /**
     * Sends a simulated scenario for the server to use.
     *
     * To be used for internal testing purposes only.
     * @suppress
     */
    fun sendSimulateScenario(scenario: LivekitRtc.SimulateScenario) {
        engine.client.sendSimulateScenario(scenario)
    }

    /**
     * Sends a simulated scenario for the server to use.
     *
     * To be used for internal testing purposes only.
     * @suppress
     */
    fun sendSimulateScenario(scenario: SimulateScenario) {
        val builder = LivekitRtc.SimulateScenario.newBuilder()
        when (scenario) {
            SimulateScenario.SPEAKER_UPDATE -> builder.speakerUpdate = 5
            SimulateScenario.NODE_FAILURE -> builder.nodeFailure = true
            SimulateScenario.MIGRATION -> builder.migration = true
            SimulateScenario.SERVER_LEAVE -> builder.serverLeave = true
            SimulateScenario.SERVER_LEAVE_FULL_RECONNECT -> builder.leaveRequestFullReconnect = true
        }
        sendSimulateScenario(builder.build())
    }

    /**
     * @suppress
     */
    @AssistedFactory
    interface Factory {
        fun create(context: Context): Room
    }

    // ------------------------------------- General Utility functions -------------------------------------//

    /**
     * Control muting/unmuting all audio output.
     *
     * Note: using this mute will only mute local audio output, and will still receive the data from
     * remote audio tracks. Consider turning off [ConnectOptions.autoSubscribe] and manually unsubscribing
     * if you need more fine-grained control over the data usage.
     **/
    fun setSpeakerMute(muted: Boolean) {
        audioDeviceModule.setSpeakerMute(muted)
    }

    /**
     * Control muting/unmuting the audio input.
     *
     * Note: using this mute will only zero the microphone audio data, and will still send data to
     * remote participants (although they will not hear anything). Consider using [TrackPublication.muted]
     * for more fine-grained control over the data usage.
     */
    fun setMicrophoneMute(muted: Boolean) {
        audioDeviceModule.setMicrophoneMute(muted)
    }

    // ------------------------------------- NetworkCallback -------------------------------------//
    private val networkCallbackManager = networkCallbackManagerFactory.invoke(
        object : NetworkCallback() {
            override fun onLost(network: Network) {
                // lost connection, flip to reconnecting
                hasLostConnectivity = true
            }

            override fun onAvailable(network: Network) {
                // only actually reconnect after connection is re-established
                if (!hasLostConnectivity) {
                    return
                }
                LKLog.i { "network connection available, reconnecting" }
                reconnect()
                hasLostConnectivity = false
            }
        },
    )

    // ----------------------------------- RTCEngine.Listener ------------------------------------//

    /**
     * @suppress
     */
    override fun onEngineConnected() {
        state = State.CONNECTED
        eventBus.postEvent(RoomEvent.Connected(this), coroutineScope)
    }

    /**
     * @suppress
     */
    override fun onEngineReconnected() {
        state = State.CONNECTED
        eventBus.postEvent(RoomEvent.Reconnected(this), coroutineScope)
    }

    /**
     * @suppress
     */
    override fun onEngineReconnecting() {
        state = State.RECONNECTING
        eventBus.postEvent(RoomEvent.Reconnecting(this), coroutineScope)
    }

    /**
     * @suppress
     */
    override fun onAddTrack(receiver: RtpReceiver, track: MediaStreamTrack, streams: Array<out MediaStream>) {
        if (streams.count() < 0) {
            LKLog.i { "add track with empty streams?" }
            return
        }

        var (participantSid, streamId) = unpackStreamId(streams.first().id)
        var trackSid = track.id()

        if (streamId != null && streamId.startsWith("TR")) {
            trackSid = streamId
        }
        val participant = getParticipantBySid(participantSid) as? RemoteParticipant

        if (participant == null) {
            LKLog.e { "Tried to add a track for a participant that is not present. sid: $participantSid" }
            return
        }

        val statsGetter = engine.createStatsGetter(receiver)
        participant.addSubscribedMediaTrack(
            track,
            trackSid!!,
            autoManageVideo = adaptiveStream,
            statsGetter = statsGetter,
            receiver = receiver,
        )
    }

    /**
     * @suppress
     */
    override fun onUpdateParticipants(updates: List<LivekitModels.ParticipantInfo>) {
        for (info in updates) {
            val participantSid = Participant.Sid(info.sid)
            // LiveKit server doesn't send identity info prior to version 1.5.2 in disconnect updates
            // so we try to map an empty identity to an already known sID manually

            @Suppress("NAME_SHADOWING") var info = info
            if (info.identity.isNullOrBlank()) {
                info = with(info.toBuilder()) {
                    identity = sidToIdentity[participantSid]?.value ?: ""
                    build()
                }
            }

            val participantIdentity = Participant.Identity(info.identity)

            if (localParticipant.identity == participantIdentity) {
                localParticipant.updateFromInfo(info)
                continue
            }

            val isNewParticipant = !remoteParticipants.contains(participantIdentity)

            if (info.state == LivekitModels.ParticipantInfo.State.DISCONNECTED) {
                handleParticipantDisconnect(participantIdentity)
            } else {
                val participant = getOrCreateRemoteParticipant(participantIdentity, info)
                if (isNewParticipant) {
                    eventBus.postEvent(RoomEvent.ParticipantConnected(this, participant), coroutineScope)
                } else {
                    participant.updateFromInfo(info)
                    sidToIdentity[participantSid] = participantIdentity
                }
            }
        }
    }

    /**
     * @suppress
     */
    override fun onActiveSpeakersUpdate(speakers: List<LivekitModels.SpeakerInfo>) {
        handleActiveSpeakersUpdate(speakers)
    }

    /**
     * @suppress
     */
    override fun onRemoteMuteChanged(trackSid: String, muted: Boolean) {
        localParticipant.onRemoteMuteChanged(trackSid, muted)
    }

    /**
     * @suppress
     */
    override fun onRoomUpdate(update: LivekitModels.Room) {
        if (update.sid != null) {
            sid = Sid(update.sid)
        }
        val oldMetadata = metadata
        metadata = update.metadata

        val oldIsRecording = isRecording
        isRecording = update.activeRecording

        if (oldMetadata != metadata) {
            eventBus.postEvent(RoomEvent.RoomMetadataChanged(this, metadata, oldMetadata), coroutineScope)
        }

        if (oldIsRecording != isRecording) {
            eventBus.postEvent(RoomEvent.RecordingStatusChanged(this, isRecording), coroutineScope)
        }
    }

    /**
     * @suppress
     */
    override fun onConnectionQuality(updates: List<LivekitRtc.ConnectionQualityInfo>) {
        updates.forEach { info ->
            val quality = ConnectionQuality.fromProto(info.quality)
            val participant = getParticipantBySid(info.participantSid) ?: return
            participant.connectionQuality = quality
            eventBus.postEvent(RoomEvent.ConnectionQualityChanged(this, participant, quality), coroutineScope)
        }
    }

    /**
     * @suppress
     */
    override fun onSpeakersChanged(speakers: List<LivekitModels.SpeakerInfo>) {
        handleSpeakersChanged(speakers)
    }

    /**
     * @suppress
     */
    override fun onUserPacket(packet: LivekitModels.UserPacket, kind: LivekitModels.DataPacket.Kind) {
        val participant = getParticipantBySid(packet.participantSid) as? RemoteParticipant
        val data = packet.payload.toByteArray()
        val topic = if (packet.hasTopic()) {
            packet.topic
        } else {
            null
        }

        eventBus.postEvent(RoomEvent.DataReceived(this, data, participant, topic), coroutineScope)
        participant?.onDataReceived(data, topic)
    }

    /**
     * @suppress
     */
    override fun onTranscriptionReceived(transcription: LivekitModels.Transcription) {
        if (transcription.segmentsList.isEmpty()) {
            LKLog.d { "Received transcription segments are empty." }
            return
        }

        val participant = getParticipantByIdentity(transcription.transcribedParticipantIdentity)
        val publication = participant?.trackPublications?.get(transcription.trackId)
        val segments = transcription.segmentsList
            .map { it.toSDKType(firstReceivedTime = transcriptionReceivedTimes[it.id] ?: Date().time) }

        // Update receive times
        for (segment in segments) {
            if (segment.final) {
                transcriptionReceivedTimes.remove(segment.id)
            } else {
                transcriptionReceivedTimes[segment.id] = segment.firstReceivedTime
            }
        }

        val event = RoomEvent.TranscriptionReceived(
            room = this,
            transcriptionSegments = segments,
            participant = participant,
            publication = publication,
        )
        eventBus.tryPostEvent(event)
        participant?.onTranscriptionReceived(event)
        publication?.onTranscriptionReceived(event)
    }

    override fun onRpcPacketReceived(dp: LivekitModels.DataPacket) {
        localParticipant.handleDataPacket(dp)
    }

    /**
     * @suppress
     */
    override fun onStreamStateUpdate(streamStates: List<LivekitRtc.StreamStateInfo>) {
        for (streamState in streamStates) {
            val participant = getParticipantBySid(streamState.participantSid) ?: continue
            val track = participant.trackPublications[streamState.trackSid] ?: continue

            track.track?.streamState = Track.StreamState.fromProto(streamState.state)
        }
    }

    /**
     * @suppress
     */
    override fun onSubscribedQualityUpdate(subscribedQualityUpdate: LivekitRtc.SubscribedQualityUpdate) {
        localParticipant.handleSubscribedQualityUpdate(subscribedQualityUpdate)
    }

    /**
     * @suppress
     */
    override fun onSubscriptionPermissionUpdate(subscriptionPermissionUpdate: LivekitRtc.SubscriptionPermissionUpdate) {
        val participant = getParticipantBySid(subscriptionPermissionUpdate.participantSid) as? RemoteParticipant ?: return
        participant.onSubscriptionPermissionUpdate(subscriptionPermissionUpdate)
    }

    /**
     * @suppress
     */
    override fun onEngineDisconnected(reason: DisconnectReason) {
        LKLog.v { "engine did disconnect: $reason" }
        handleDisconnect(reason)
    }

    /**
     * @suppress
     */
    override fun onFailToConnect(error: Throwable) {
        // scope will likely be closed already here, so force it out of scope.
        eventBus.tryPostEvent(RoomEvent.FailedToConnect(this, error))
    }

    /**
     * @suppress
     */
    override fun onSignalConnected(isResume: Boolean) {
        if (isResume) {
            // during resume reconnection, need to send sync state upon signal connection.
            sendSyncState()
        }
    }

    /**
     * @suppress
     */
    override fun onFullReconnecting() {
        localParticipant.prepareForFullReconnect()
        remoteParticipants.keys.toMutableSet() // copy keys to avoid concurrent modifications.
            .forEach { identity -> handleParticipantDisconnect(identity) }
    }

    /**
     * @suppress
     */
    override suspend fun onPostReconnect(isFullReconnect: Boolean) {
        if (isFullReconnect) {
            localParticipant.republishTracks()
        } else {
            val remoteParticipants = remoteParticipants.values.toList()
            for (participant in remoteParticipants) {
                val pubs = participant.trackPublications.values.toList()
                for (pub in pubs) {
                    val remotePub = pub as? RemoteTrackPublication ?: continue
                    if (remotePub.subscribed) {
                        remotePub.sendUpdateTrackSettings.invoke()
                    }
                }
            }
        }
    }

    /**
     * @suppress
     */
    override fun onLocalTrackSubscribed(response: LivekitRtc.TrackSubscribed) {
        val publication = localParticipant.trackPublications[response.trackSid] as? LocalTrackPublication

        if (publication == null) {
            LKLog.w { "Could not find local track publication for subscribed event " }
            return
        }

        coroutineScope.launch {
            emitWhenConnected(RoomEvent.LocalTrackSubscribed(this@Room, publication, this@Room.localParticipant))
        }
        this.localParticipant.onLocalTrackSubscribed(publication)
    }

    /**
     * @suppress
     */
    override fun onLocalTrackUnpublished(trackUnpublished: LivekitRtc.TrackUnpublishedResponse) {
        localParticipant.handleLocalTrackUnpublished(trackUnpublished)
    }

    // ------------------------------- ParticipantListener --------------------------------//
    /**
     * This is called for both Local and Remote participants
     * @suppress
     */
    override fun onMetadataChanged(participant: Participant, prevMetadata: String?) {
    }

    /** @suppress */
    override fun onTrackMuted(publication: TrackPublication, participant: Participant) {
        eventBus.postEvent(RoomEvent.TrackMuted(this, publication, participant), coroutineScope)
    }

    /** @suppress */
    override fun onTrackUnmuted(publication: TrackPublication, participant: Participant) {
        eventBus.postEvent(RoomEvent.TrackUnmuted(this, publication, participant), coroutineScope)
    }

    /**
     * @suppress
     */
    override fun onTrackUnpublished(publication: RemoteTrackPublication, participant: RemoteParticipant) {
        eventBus.postEvent(RoomEvent.TrackUnpublished(this, publication, participant), coroutineScope)
    }

    /**
     * @suppress
     */
    override fun onTrackPublished(publication: LocalTrackPublication, participant: LocalParticipant) {
        if (e2eeManager != null) {
            e2eeManager!!.addPublishedTrack(publication.track!!, publication, participant, this)
        }
        eventBus.postEvent(RoomEvent.TrackPublished(this, publication, participant), coroutineScope)
    }

    /**
     * @suppress
     */
    override fun onTrackUnpublished(publication: LocalTrackPublication, participant: LocalParticipant) {
        e2eeManager?.let { e2eeManager ->
            e2eeManager!!.removePublishedTrack(publication.track!!, publication, participant, this)
        }
        eventBus.postEvent(RoomEvent.TrackUnpublished(this, publication, participant), coroutineScope)
    }

    /**
     * @suppress
     */
    override fun onTrackSubscribed(track: Track, publication: RemoteTrackPublication, participant: RemoteParticipant) {
        if (e2eeManager != null) {
            e2eeManager!!.addSubscribedTrack(track, publication, participant, this)
        }
        eventBus.postEvent(RoomEvent.TrackSubscribed(this, track, publication, participant), coroutineScope)
    }

    /**
     * @suppress
     */
    override fun onTrackSubscriptionFailed(
        sid: String,
        exception: Exception,
        participant: RemoteParticipant,
    ) {
        eventBus.postEvent(RoomEvent.TrackSubscriptionFailed(this, sid, exception, participant), coroutineScope)
    }

    /**
     * @suppress
     */
    override fun onTrackUnsubscribed(
        track: Track,
        publication: RemoteTrackPublication,
        participant: RemoteParticipant,
    ) {
        e2eeManager?.let { e2eeManager ->
            e2eeManager!!.removeSubscribedTrack(track, publication, participant, this)
        }
        eventBus.postEvent(RoomEvent.TrackUnsubscribed(this, track, publication, participant), coroutineScope)
    }

    /**
     * Initialize a [SurfaceViewRenderer] for rendering a video from this room.
     */
    // TODO(@dl): can this be moved out of Room/SDK?
    fun initVideoRenderer(viewRenderer: SurfaceViewRenderer) {
        viewRenderer.init(eglBase.eglBaseContext, null)
        viewRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        viewRenderer.setEnableHardwareScaler(false)
    }

    /**
     * Initialize a [TextureViewRenderer] for rendering a video from this room.
     */
    // TODO(@dl): can this be moved out of Room/SDK?
    fun initVideoRenderer(viewRenderer: TextureViewRenderer) {
        viewRenderer.init(eglBase.eglBaseContext, null)
        viewRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        viewRenderer.setEnableHardwareScaler(false)
    }

    private suspend fun emitWhenConnected(event: RoomEvent) {
        if (state == State.CONNECTED) {
            eventBus.postEvent(event)
        }
    }

    /**
     * Get stats for the publisher peer connection.
     *
     * @see getSubscriberRTCStats
     * @see getFilteredStats
     */
    fun getPublisherRTCStats(callback: RTCStatsCollectorCallback) = engine.getPublisherRTCStats(callback)

    /**
     * Get stats for the subscriber peer connection.
     *
     * @see getPublisherRTCStats
     * @see getFilteredStats
     */
    fun getSubscriberRTCStats(callback: RTCStatsCollectorCallback) = engine.getSubscriberRTCStats(callback)

    // Debug options

    /**
     * @suppress
     */
    @VisibleForTesting
    fun setReconnectionType(reconnectType: ReconnectType) {
        engine.reconnectType = reconnectType
    }
}

sealed class RoomException(message: String? = null, cause: Throwable? = null) :
    Exception(message, cause) {
    class ConnectException(message: String? = null, cause: Throwable? = null) :
        RoomException(message, cause)
}

internal fun unpackStreamId(packed: String): Pair<String, String?> {
    val parts = packed.split('|')
    if (parts.size != 2) {
        return Pair(packed, null)
    }
    return Pair(parts[0], parts[1])
}
