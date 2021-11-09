package io.livekit.android.room.track

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import io.livekit.android.room.DefaultsManager
import io.livekit.android.room.track.screencapture.ScreenCaptureConnection
import org.webrtc.*
import java.util.*

class LocalScreencastVideoTrack
@AssistedInject
constructor(
    @Assisted capturer: VideoCapturer,
    @Assisted source: VideoSource,
    @Assisted name: String,
    @Assisted options: LocalVideoTrackOptions,
    @Assisted rtcTrack: org.webrtc.VideoTrack,
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
    videoTrackFactory
) {

    private val serviceConnection = ScreenCaptureConnection(context)

    init {
        mediaProjectionCallback.onStopCallback = { stop() }
    }

    /**
     * A foreground service is generally required prior to [startCapture]. This method starts up
     * a helper foreground service that only serves to display a notification while capturing. This
     * foreground service will stop upon the end of screen capture.
     *
     * You may choose to use your own foreground service instead of this method, but it must be
     * started prior to calling [startCapture].
     *
     * @see [io.livekit.android.room.track.screencapture.ScreenCaptureService.start]
     */
    suspend fun startForegroundService(notificationId: Int?, notification: Notification?) {
        serviceConnection.connect()
        serviceConnection.startForeground(notificationId, notification)
    }

    override fun stop() {
        super.stop()
        serviceConnection.stop()
    }

    @AssistedFactory
    interface Factory {
        fun create(
            capturer: VideoCapturer,
            source: VideoSource,
            name: String,
            options: LocalVideoTrackOptions,
            rtcTrack: org.webrtc.VideoTrack,
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
            screencastVideoTrackFactory: Factory
        ): LocalScreencastVideoTrack {
            val source = peerConnectionFactory.createVideoSource(options.isScreencast)
            val callback = MediaProjectionCallback()
            val capturer = createScreenCapturer(mediaProjectionPermissionResultData, callback)
            capturer.initialize(
                SurfaceTextureHelper.create("ScreenVideoCaptureThread", rootEglBase.eglBaseContext),
                context,
                source.capturerObserver
            )
            val track = peerConnectionFactory.createVideoTrack(UUID.randomUUID().toString(), source)

            return screencastVideoTrackFactory.create(
                capturer = capturer,
                source = source,
                options = options,
                name = name,
                rtcTrack = track,
                mediaProjectionCallback = callback
            )
        }


        private fun createScreenCapturer(
            resultData: Intent,
            callback: MediaProjectionCallback
        ): ScreenCapturerAndroid {
            return ScreenCapturerAndroid(resultData, callback)
        }
    }
}