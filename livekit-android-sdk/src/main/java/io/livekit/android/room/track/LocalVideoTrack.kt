/*
 * Copyright 2023-2024 LiveKit, Inc.
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

package io.livekit.android.room.track

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import io.livekit.android.memory.CloseableManager
import io.livekit.android.memory.SurfaceTextureHelperCloser
import io.livekit.android.room.DefaultsManager
import io.livekit.android.room.track.video.CameraCapturerUtils
import io.livekit.android.room.track.video.CameraCapturerUtils.createCameraEnumerator
import io.livekit.android.room.track.video.CameraCapturerUtils.findCamera
import io.livekit.android.room.track.video.CameraCapturerUtils.getCameraPosition
import io.livekit.android.room.track.video.CameraCapturerWithSize
import io.livekit.android.room.track.video.CaptureDispatchObserver
import io.livekit.android.room.track.video.ScaleCropVideoProcessor
import io.livekit.android.room.track.video.VideoCapturerWithSize
import io.livekit.android.room.util.EncodingUtils
import io.livekit.android.util.FlowObservable
import io.livekit.android.util.LKLog
import io.livekit.android.util.flowDelegate
import livekit.LivekitModels
import livekit.LivekitRtc
import livekit.LivekitRtc.SubscribedCodec
import livekit.org.webrtc.CameraVideoCapturer
import livekit.org.webrtc.CameraVideoCapturer.CameraEventsHandler
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.MediaStreamTrack
import livekit.org.webrtc.PeerConnectionFactory
import livekit.org.webrtc.RtpParameters
import livekit.org.webrtc.RtpSender
import livekit.org.webrtc.RtpTransceiver
import livekit.org.webrtc.SurfaceTextureHelper
import livekit.org.webrtc.VideoCapturer
import livekit.org.webrtc.VideoProcessor
import livekit.org.webrtc.VideoSink
import livekit.org.webrtc.VideoSource
import java.util.UUID
import livekit.LivekitModels.VideoQuality as ProtoVideoQuality

/**
 * A representation of a local video track (generally input coming from camera or screen).
 *
 * [startCapture] should be called before use.
 */
open class LocalVideoTrack
@AssistedInject
constructor(
    @Assisted capturer: VideoCapturer,
    @Assisted private var source: VideoSource,
    @Assisted name: String,
    @Assisted options: LocalVideoTrackOptions,
    @Assisted rtcTrack: livekit.org.webrtc.VideoTrack,
    private val peerConnectionFactory: PeerConnectionFactory,
    private val context: Context,
    private val eglBase: EglBase,
    private val defaultsManager: DefaultsManager,
    private val trackFactory: Factory,
    /**
     * If this is assigned, you must ensure that this observer is associated with the [capturer],
     * as this will be used to receive frames in [addRenderer].
     **/
    @Assisted private var dispatchObserver: CaptureDispatchObserver? = null,
) : VideoTrack(name, rtcTrack) {

    var capturer = capturer
        private set

    override var rtcTrack: livekit.org.webrtc.VideoTrack = rtcTrack
        internal set

    internal var codec: String? = null
    private var subscribedCodecs: List<SubscribedCodec>? = null
    private val simulcastCodecs = mutableMapOf<VideoCodec, SimulcastTrackInfo>()

    @FlowObservable
    @get:FlowObservable
    var options: LocalVideoTrackOptions by flowDelegate(options)

    val dimensions: Dimensions
        get() {
            (capturer as? VideoCapturerWithSize)?.let { capturerWithSize ->
                val size = capturerWithSize.findCaptureFormat(
                    options.captureParams.width,
                    options.captureParams.height,
                )
                return Dimensions(size.width, size.height)
            }
            return Dimensions(options.captureParams.width, options.captureParams.height)
        }

    internal var transceiver: RtpTransceiver? = null
    internal val sender: RtpSender?
        get() = transceiver?.sender

    private val closeableManager = CloseableManager()

    /**
     * Starts the [capturer] with the capture params contained in [options].
     */
    open fun startCapture() {
        capturer.startCapture(
            options.captureParams.width,
            options.captureParams.height,
            options.captureParams.maxFps,
        )
    }

    /**
     * Stops the [capturer].
     */
    open fun stopCapture() {
        capturer.stopCapture()
    }

    override fun stop() {
        capturer.stopCapture()
        super.stop()
    }

    override fun dispose() {
        super.dispose()
        capturer.dispose()
        closeableManager.close()
    }

    override fun addRenderer(renderer: VideoSink) {
        if (dispatchObserver != null) {
            dispatchObserver?.registerSink(renderer)
        } else {
            super.addRenderer(renderer)
        }
    }

    override fun removeRenderer(renderer: VideoSink) {
        if (dispatchObserver != null) {
            dispatchObserver?.unregisterSink(renderer)
        } else {
            super.removeRenderer(renderer)
        }
    }

    /**
     * If this is a camera track, switches to the new camera determined by [deviceId]
     */
    @Deprecated("Use LocalVideoTrack.switchCamera instead.", ReplaceWith("switchCamera(deviceId = deviceId)"))
    fun setDeviceId(deviceId: String) {
        restartTrack(options.copy(deviceId = deviceId))
    }

    /**
     * Switch to a different camera. Only works if this track is backed by a camera capturer.
     *
     * If neither deviceId or position is provided, or the specified camera cannot be found,
     * this will switch to the next camera, if one is available.
     */
    fun switchCamera(deviceId: String? = null, position: CameraPosition? = null) {
        val cameraCapturer = capturer as? CameraVideoCapturer ?: run {
            LKLog.w { "Attempting to switch camera on a non-camera video track!" }
            return
        }

        var targetDeviceId: String? = null
        val enumerator = createCameraEnumerator(context)
        if (deviceId != null || position != null) {
            targetDeviceId = enumerator.findCamera(deviceId, position, fallback = false)
        }

        if (targetDeviceId == null) {
            val deviceNames = enumerator.deviceNames
            if (deviceNames.size < 2) {
                LKLog.w { "No available cameras to switch to!" }
                return
            }
            val currentIndex = deviceNames.indexOf(options.deviceId)
            targetDeviceId = deviceNames[(currentIndex + 1) % deviceNames.size]
        }

        fun updateCameraOptions() {
            val newOptions = options.copy(
                deviceId = targetDeviceId,
                position = enumerator.getCameraPosition(targetDeviceId),
            )
            options = newOptions
        }

        val cameraSwitchHandler = object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontFacing: Boolean) {
                // For cameras we control, wait until the first frame to ensure everything is okay.
                if (cameraCapturer is CameraCapturerWithSize) {
                    cameraCapturer.cameraEventsDispatchHandler
                        .registerHandler(
                            object : CameraEventsHandler {
                                override fun onFirstFrameAvailable() {
                                    updateCameraOptions()
                                    cameraCapturer.cameraEventsDispatchHandler.unregisterHandler(this)
                                }

                                override fun onCameraError(p0: String?) {
                                    cameraCapturer.cameraEventsDispatchHandler.unregisterHandler(this)
                                }

                                override fun onCameraDisconnected() {
                                    cameraCapturer.cameraEventsDispatchHandler.unregisterHandler(this)
                                }

                                override fun onCameraFreezed(p0: String?) {
                                }

                                override fun onCameraOpening(p0: String?) {
                                }

                                override fun onCameraClosed() {
                                    cameraCapturer.cameraEventsDispatchHandler.unregisterHandler(this)
                                }
                            },
                        )
                } else {
                    updateCameraOptions()
                }
            }

            override fun onCameraSwitchError(errorDescription: String?) {
                LKLog.w { "switching camera failed: $errorDescription" }
            }
        }
        if (targetDeviceId == null) {
            LKLog.w { "No target camera found!" }
            return
        } else {
            cameraCapturer.switchCamera(cameraSwitchHandler, targetDeviceId)
        }
    }

    /**
     * Restart a track with new options.
     */
    fun restartTrack(options: LocalVideoTrackOptions = defaultsManager.videoTrackCaptureDefaults.copy()) {
        if (isDisposed) {
            LKLog.e { "Attempting to restart track that was already disposed, aborting." }
            return
        }

        val oldCapturer = capturer
        val oldSource = source
        val oldRtcTrack = rtcTrack

        oldCapturer.stopCapture()
        oldCapturer.dispose()
        oldSource.dispose()

        oldRtcTrack.setEnabled(false)

        // We always own our copy of rtcTrack, so we need to dispose it.
        // Note: For the first rtcTrack we pass to the PeerConnection, PeerConnection actually
        // passes it down to the native, and then ends up holding onto a separate copy at the
        // Java layer. This means our initial rtcTrack isn't owned by PeerConnection, and is
        // our responsibility to dispose.
        oldRtcTrack.dispose()

        // Close resources associated to the old track. new track resources is registered in createTrack.
        val oldCloseable = closeableManager.unregisterResource(oldRtcTrack)
        oldCloseable?.close()

        val newTrack = createCameraTrack(
            peerConnectionFactory,
            context,
            name,
            options,
            eglBase,
            trackFactory,
        )

        // migrate video sinks to the new track
        for (sink in sinks) {
            oldRtcTrack.removeSink(sink)
            newTrack.addRenderer(sink)
        }

        capturer = newTrack.capturer
        source = newTrack.source
        rtcTrack = newTrack.rtcTrack
        this.options = options
        startCapture()
        sender?.setTrack(newTrack.rtcTrack, false)
    }

    internal fun setPublishingLayers(
        qualities: List<LivekitRtc.SubscribedQuality>,
    ) {
        val sender = transceiver?.sender ?: return

        setPublishingLayersForSender(sender, qualities)
    }

    private fun setPublishingLayersForSender(
        sender: RtpSender,
        qualities: List<LivekitRtc.SubscribedQuality>,
    ) {
        if (isDisposed) {
            LKLog.i { "attempted to set publishing layer for disposed video track." }
            return
        }
        try {
            val parameters = sender.parameters ?: return
            val encodings = parameters.encodings ?: return
            var hasChanged = false

            if (encodings.firstOrNull()?.scalabilityMode != null) {
                val encoding = encodings.first()
                var maxQuality = ProtoVideoQuality.OFF
                for (quality in qualities) {
                    if (quality.enabled && (maxQuality == ProtoVideoQuality.OFF || quality.quality.number > maxQuality.number)) {
                        maxQuality = quality.quality
                    }
                }

                if (maxQuality == ProtoVideoQuality.OFF) {
                    if (encoding.active) {
                        LKLog.v { "setting svc track to disabled" }
                        encoding.active = false
                        hasChanged = true
                    }
                } else if (!encoding.active) {
                    LKLog.v { "setting svc track to enabled" }
                    encoding.active = true
                    hasChanged = true
                }
            } else {
                // simulcast dynacast encodings
                for (quality in qualities) {
                    val rid = EncodingUtils.ridForVideoQuality(quality.quality) ?: continue
                    val encoding = encodings.firstOrNull { it.rid == rid }
                        // use low quality layer settings for non-simulcasted streams
                        ?: encodings.takeIf { it.size == 1 && quality.quality == LivekitModels.VideoQuality.LOW }?.first()
                        ?: continue
                    if (encoding.active != quality.enabled) {
                        hasChanged = true
                        encoding.active = quality.enabled
                        LKLog.v { "setting layer ${quality.quality} to ${quality.enabled}" }
                    }
                }
            }

            if (hasChanged) {
                // This refreshes the native code with the new information
                sender.parameters = parameters
            }
        } catch (e: Exception) {
            LKLog.w(e) { "Exception caught while setting publishing layers." }
            return
        }
    }

    internal fun setPublishingCodecs(codecs: List<SubscribedCodec>): List<VideoCodec> {
        LKLog.v { "setting publishing codecs: $codecs" }

        // only enable simulcast codec for preferred codec set
        if (this.codec == null && codecs.isNotEmpty()) {
            setPublishingLayers(codecs.first().qualitiesList)
            return emptyList()
        }

        this.subscribedCodecs = codecs

        val newCodecs = mutableListOf<VideoCodec>()

        for (codec in codecs) {
            if (this.codec == codec.codec) {
                setPublishingLayers(codec.qualitiesList)
            } else {
                val videoCodec = try {
                    VideoCodec.fromCodecName(codec.codec)
                } catch (e: Exception) {
                    LKLog.w { "unknown publishing codec ${codec.codec}!" }
                    continue
                }

                LKLog.d { "try setPublishingCodec for ${codec.codec}" }
                val simulcastInfo = this.simulcastCodecs[videoCodec]
                if (simulcastInfo?.sender == null) {
                    for (q in codec.qualitiesList) {
                        if (q.enabled) {
                            newCodecs.add(videoCodec)
                            break
                        }
                    }
                } else {
                    LKLog.d { "try setPublishingLayersForSender ${codec.codec}" }
                    setPublishingLayersForSender(
                        simulcastInfo.sender!!,
                        codec.qualitiesList,
                    )
                }
            }
        }
        return newCodecs
    }

    internal fun addSimulcastTrack(codec: VideoCodec, encodings: List<RtpParameters.Encoding>): SimulcastTrackInfo {
        if (this.simulcastCodecs.containsKey(codec)) {
            throw IllegalStateException("$codec already added!")
        }
        val simulcastTrackInfo = SimulcastTrackInfo(
            codec = codec.codecName,
            rtcTrack = rtcTrack,
            encodings = encodings,
        )
        simulcastCodecs[codec] = simulcastTrackInfo
        return simulcastTrackInfo
    }

    @AssistedFactory
    interface Factory {
        fun create(
            capturer: VideoCapturer,
            source: VideoSource,
            name: String,
            options: LocalVideoTrackOptions,
            rtcTrack: livekit.org.webrtc.VideoTrack,
            dispatchObserver: CaptureDispatchObserver?,
        ): LocalVideoTrack
    }

    companion object {

        internal fun createCameraTrack(
            peerConnectionFactory: PeerConnectionFactory,
            context: Context,
            name: String,
            options: LocalVideoTrackOptions,
            rootEglBase: EglBase,
            trackFactory: Factory,
            videoProcessor: VideoProcessor? = null,
        ): LocalVideoTrack {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                throw SecurityException("Camera permissions are required to create a camera video track.")
            }

            val (capturer, newOptions) = CameraCapturerUtils.createCameraCapturer(context, options) ?: TODO()

            return createTrack(
                peerConnectionFactory = peerConnectionFactory,
                context = context,
                name = name,
                capturer = capturer,
                options = newOptions,
                rootEglBase = rootEglBase,
                trackFactory = trackFactory,
                videoProcessor = videoProcessor,
            )
        }

        internal fun createTrack(
            peerConnectionFactory: PeerConnectionFactory,
            context: Context,
            name: String,
            capturer: VideoCapturer,
            options: LocalVideoTrackOptions = LocalVideoTrackOptions(),
            rootEglBase: EglBase,
            trackFactory: Factory,
            videoProcessor: VideoProcessor? = null,
        ): LocalVideoTrack {
            val source = peerConnectionFactory.createVideoSource(options.isScreencast)

            val finalVideoProcessor = if (options.captureParams.adaptOutputToDimensions) {
                ScaleCropVideoProcessor(
                    targetWidth = options.captureParams.width,
                    targetHeight = options.captureParams.height,
                ).apply {
                    childVideoProcessor = videoProcessor
                }
            } else {
                videoProcessor
            }
            source.setVideoProcessor(finalVideoProcessor)

            val surfaceTextureHelper = SurfaceTextureHelper.create("VideoCaptureThread", rootEglBase.eglBaseContext)

            // Dispatch raw frames to local renderer only if not using a user-provided VideoProcessor.
            val dispatchObserver = if (videoProcessor == null) {
                CaptureDispatchObserver().apply {
                    registerObserver(source.capturerObserver)
                }
            } else {
                null
            }

            capturer.initialize(
                surfaceTextureHelper,
                context,
                dispatchObserver ?: source.capturerObserver,
            )
            val rtcTrack = peerConnectionFactory.createVideoTrack(UUID.randomUUID().toString(), source)

            val track = trackFactory.create(
                capturer = capturer,
                source = source,
                options = options,
                name = name,
                rtcTrack = rtcTrack,
                dispatchObserver = dispatchObserver,
            )

            track.closeableManager.registerResource(
                rtcTrack,
                SurfaceTextureHelperCloser(surfaceTextureHelper),
            )
            return track
        }
    }
}

internal data class SimulcastTrackInfo(
    var codec: String,
    var rtcTrack: MediaStreamTrack,
    var sender: RtpSender? = null,
    var encodings: List<RtpParameters.Encoding>? = null,
)
