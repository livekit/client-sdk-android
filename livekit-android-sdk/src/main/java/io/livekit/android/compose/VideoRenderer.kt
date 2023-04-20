package io.livekit.android.compose

import android.graphics.Matrix
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.viewinterop.AndroidView
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.track.RemoteVideoTrack
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.room.track.video.ComposeVisibility

/**
 * Widget for displaying a VideoTrack. Handles the Compose <-> AndroidView interop needed to use
 * [TextureViewRenderer].
 */
@Composable
fun VideoRenderer(
    room: Room,
    videoTrack: VideoTrack,
    modifier: Modifier = Modifier,
    mirror: Boolean = false,
) {

    val videoSinkVisibility = remember(room, videoTrack) { ComposeVisibility() }
    var boundVideoTrack by remember { mutableStateOf<VideoTrack?>(null) }
    var view: TextureViewRenderer? by remember { mutableStateOf(null) }
    var videoScale by remember { mutableStateOf(1f) }

    videoScale = if (mirror) {
        -1f
    } else {
        1f
    }

    fun cleanupVideoTrack() {
        view?.let { boundVideoTrack?.removeRenderer(it) }
        boundVideoTrack = null
    }

    fun setupVideoIfNeeded(videoTrack: VideoTrack, view: TextureViewRenderer) {
        if (boundVideoTrack == videoTrack) {
            return
        }

        cleanupVideoTrack()

        boundVideoTrack = videoTrack
        if (videoTrack is RemoteVideoTrack) {
            videoTrack.addRenderer(view, videoSinkVisibility)
        } else {
            videoTrack.addRenderer(view)
        }
    }

    DisposableEffect(view, videoScale) {
        view?.scaleX = videoScale
        onDispose { }
    }

    DisposableEffect(room, videoTrack) {
        onDispose {
            videoSinkVisibility.onDispose()
            cleanupVideoTrack()
        }
    }

    DisposableEffect(currentCompositeKeyHash.toString()) {
        onDispose {
            view?.release()
        }
    }

    AndroidView(
        factory = { context ->
            TextureViewRenderer(context).apply {
                room.initVideoRenderer(this)
                setupVideoIfNeeded(videoTrack, this)

                view = this
            }
        },
        update = { v -> setupVideoIfNeeded(videoTrack, v) },
        modifier = modifier
            .onGloballyPositioned { videoSinkVisibility.onGloballyPositioned(it) },
    )
}