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
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.view.OrientationEventListener
import android.view.WindowManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import io.livekit.android.room.DefaultsManager
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.track.screencapture.ScreenCaptureConnection
import io.livekit.android.room.track.screencapture.ScreenCaptureService
import io.livekit.android.util.LKLog
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.PeerConnectionFactory
import livekit.org.webrtc.ScreenCapturerAndroid
import livekit.org.webrtc.SurfaceTextureHelper
import livekit.org.webrtc.VideoCapturer
import livekit.org.webrtc.VideoProcessor
import livekit.org.webrtc.VideoSource
import java.util.UUID

/**
 * A video track that captures the screen for publishing.
 *
 * Note: A foreground service is generally required for use. Use [startForegroundService] or start
 * your own foreground service before starting the video track.
 *
 * @see LocalParticipant.createScreencastTrack
 * @see LocalScreencastVideoTrack.startForegroundService
 */
class LocalScreencastVideoTrack
@AssistedInject
constructor(
    @Assisted capturer: VideoCapturer,
    @Assisted source: VideoSource,
    @Assisted name: String,
    @Assisted options: LocalVideoTrackOptions,
    @Assisted rtcTrack: livekit.org.webrtc.VideoTrack,
    @Assisted mediaProjectionCallback: MediaProjectionCallback,
    peerConnectionFactory: PeerConnectionFactory,
    context: Context,
    eglBase: EglBase,
    defaultsManager: DefaultsManager,
    videoTrackFactory: LocalVideoTrack.Factory,
) : LocalVideoTrack(
    capturer,
    source,
    name,
    options,
    rtcTrack,
    peerConnectionFactory,
    context,
    eglBase,
    defaultsManager,
    videoTrackFactory,
) {

    private var prevDisplayWidth = 0
    private var prevDisplayHeight = 0
    private val displayMetrics = DisplayMetrics()
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val orientationEventListener = object : OrientationEventListener(context) {
        override fun onOrientationChanged(orientation: Int) {
            if (isDisposed) {
                this.disable()
                return
            }
            updateCaptureFormatIfNeeded()
        }
    }

    private fun getCaptureDimensions(displayWidth: Int, displayHeight: Int): Pair<Int, Int> {
        val captureWidth: Int
        val captureHeight: Int

        if (options.captureParams.width == 0 && options.captureParams.height == 0) {
            // Use raw display size
            captureWidth = displayWidth
            captureHeight = displayHeight
        } else {
            // Use captureParams.width as longest side and captureParams.height as shortest side.
            if (displayWidth > displayHeight) {
                captureWidth = options.captureParams.width
                captureHeight = options.captureParams.height
            } else {
                captureWidth = options.captureParams.height
                captureHeight = options.captureParams.width
            }
        }

        return captureWidth to captureHeight
    }

    private fun updateCaptureFormatIfNeeded() {
        windowManager.defaultDisplay.getRealMetrics(displayMetrics)
        val displayWidth = displayMetrics.widthPixels
        val displayHeight = displayMetrics.heightPixels

        // Updates whenever the display rotates
        if (displayWidth != prevDisplayWidth || displayHeight != prevDisplayHeight) {
            prevDisplayWidth = displayWidth
            prevDisplayHeight = displayHeight

            val (captureWidth, captureHeight) = getCaptureDimensions(displayWidth, displayHeight)

            try {
                capturer.changeCaptureFormat(captureWidth, captureHeight, options.captureParams.maxFps)
            } catch (e: Exception) {
                LKLog.w(e) { "Exception when changing capture format of the screen share track." }
            }
        }
    }

    override fun startCapture() {
        // Don't use super.startCapture, must calculate correct dimensions
        windowManager.defaultDisplay.getRealMetrics(displayMetrics)
        val displayWidth = displayMetrics.widthPixels
        val displayHeight = displayMetrics.heightPixels
        val (captureWidth, captureHeight) = getCaptureDimensions(displayWidth, displayHeight)

        capturer.startCapture(captureWidth, captureHeight, options.captureParams.maxFps)

        if (orientationEventListener.canDetectOrientation()) {
            orientationEventListener.enable()
        }
    }

    private val serviceConnection = ScreenCaptureConnection(context)

    init {
        mediaProjectionCallback.onStopCallback = { stop() }
    }

    /**
     * A foreground service is generally required prior to [startCapture] for screen capture.
     * This method starts up a helper foreground service that only serves to display a
     * notification while capturing. This foreground service will automatically stop
     * upon the end of screen capture.
     *
     * You may choose to use your own foreground service instead of this method, but it must be
     * started prior to calling [startCapture] and kept running for the duration of the screen share.
     *
     * **Notes:** If no notification is passed, a notification channel will be created and a default
     * notification will be shown.
     *
     * Beginning with Android 13, the [Manifest.permission.POST_NOTIFICATIONS] runtime permission
     * is required to show notifications. The foreground service will work without the permission,
     * but you must add the permission to your AndroidManifest.xml and request the permission at runtime
     * if you wish for your notification to be shown.
     *
     * @see [ScreenCaptureService.start]
     *
     * @param notificationId The identifier for this notification as per [NotificationManager.notify]; must not be 0.
     *   If null, defaults to [ScreenCaptureService.DEFAULT_NOTIFICATION_ID].
     * @param notification The notification to show. If null, a default notification will be shown.
     */
    suspend fun startForegroundService(notificationId: Int?, notification: Notification?) {
        serviceConnection.connect()
        serviceConnection.startForeground(notificationId, notification)
    }

    override fun stop() {
        super.stop()
        serviceConnection.stop()
        orientationEventListener.disable()
    }

    @AssistedFactory
    interface Factory {
        fun create(
            capturer: VideoCapturer,
            source: VideoSource,
            name: String,
            options: LocalVideoTrackOptions,
            rtcTrack: livekit.org.webrtc.VideoTrack,
            mediaProjectionCallback: MediaProjectionCallback,
        ): LocalScreencastVideoTrack
    }

    /**
     * Needed to deal with circular dependency.
     */
    class MediaProjectionCallback : MediaProjection.Callback() {
        var onStopCallback: (() -> Unit)? = null

        override fun onStop() {
            onStopCallback?.invoke()
        }
    }

    companion object {
        internal fun createTrack(
            mediaProjectionPermissionResultData: Intent,
            peerConnectionFactory: PeerConnectionFactory,
            context: Context,
            name: String,
            options: LocalVideoTrackOptions,
            rootEglBase: EglBase,
            screencastVideoTrackFactory: Factory,
            videoProcessor: VideoProcessor?,
        ): LocalScreencastVideoTrack {
            val source = peerConnectionFactory.createVideoSource(options.isScreencast)
            source.setVideoProcessor(videoProcessor)
            val callback = MediaProjectionCallback()
            val capturer = createScreenCapturer(mediaProjectionPermissionResultData, callback)
            capturer.initialize(
                SurfaceTextureHelper.create("ScreenVideoCaptureThread", rootEglBase.eglBaseContext),
                context,
                source.capturerObserver,
            )
            val track = peerConnectionFactory.createVideoTrack(UUID.randomUUID().toString(), source)

            return screencastVideoTrackFactory.create(
                capturer = capturer,
                source = source,
                options = options,
                name = name,
                rtcTrack = track,
                mediaProjectionCallback = callback,
            )
        }

        private fun createScreenCapturer(
            resultData: Intent,
            callback: MediaProjectionCallback,
        ): ScreenCapturerAndroid {
            return ScreenCapturerAndroid(resultData, callback)
        }
    }
}
