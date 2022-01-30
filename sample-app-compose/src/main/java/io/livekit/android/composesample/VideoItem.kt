package io.livekit.android.composesample

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.viewinterop.AndroidView
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.RemoteVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.room.track.video.ComposeVisibility
import io.livekit.android.util.flow
import kotlinx.coroutines.flow.map

/**
 * Widget for displaying a VideoTrack. Handles the Compose <-> AndroidView interop needed to use
 * [TextureViewRenderer].
 */
@Composable
fun VideoItem(
    room: Room,
    videoTrack: VideoTrack,
    modifier: Modifier = Modifier
) {
    val videoSinkVisibility = remember(room, videoTrack) { ComposeVisibility() }
    var boundVideoTrack by remember { mutableStateOf<VideoTrack?>(null) }
    var view: TextureViewRenderer? by remember { mutableStateOf(null) }

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
        update = { view ->
            setupVideoIfNeeded(videoTrack, view)
        },
        modifier = modifier
            .onGloballyPositioned { videoSinkVisibility.onGloballyPositioned(it) },
    )
}

/**
 * This widget primarily serves as a way to observe changes in [videoTracks].
 */
@Composable
fun VideoItemTrackSelector(
    room: Room,
    participant: Participant,
    modifier: Modifier = Modifier
) {

    val subscribedVideoTracksFlow by remember(participant) {
        mutableStateOf(
            participant::videoTracks.flow
                .map { tracks -> tracks.values.filter { pub -> pub.subscribed } }
        )
    }
    val videoTracks by subscribedVideoTracksFlow.collectAsState(initial = emptyList())
    val videoTrack = videoTracks.firstOrNull { pub -> pub.source == Track.Source.SCREEN_SHARE }?.track as? VideoTrack
        ?: videoTracks.firstOrNull { pub -> pub.source == Track.Source.CAMERA }?.track as? VideoTrack
        ?: videoTracks.firstOrNull()?.track as? VideoTrack

    if (videoTrack != null) {
        VideoItem(
            room = room,
            videoTrack = videoTrack,
            modifier = modifier
        )
    }
}