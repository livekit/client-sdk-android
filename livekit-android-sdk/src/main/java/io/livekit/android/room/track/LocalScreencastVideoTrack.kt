package io.livekit.android.room.track

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import io.livekit.android.room.DefaultsManager
import io.livekit.android.room.track.screencapture.ScreenCaptureConnection
import io.livekit.android.room.track.screencapture.ScreenCaptureService
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