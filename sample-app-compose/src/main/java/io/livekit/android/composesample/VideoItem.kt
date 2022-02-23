package io.livekit.android.composesample

import androidx.compose.foundation.layout.Box
import androidx.compose.material.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.viewinterop.AndroidView
import com.github.ajalt.timberkt.Timber
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.RemoteVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.room.track.video.ComposeVisibility
import io.livekit.android.util.flow
import kotlinx.coroutines.flow.*

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
                .flatMapLatest { videoTracks ->
                    combine(
                        videoTracks.values
                            .map { trackPublication ->
                                // Re-emit when track changes
                                trackPublication::track.flow
                                    .map { trackPublication }
                            }
                    ) { trackPubs ->
                        trackPubs.toList().filter { trackPublication -> trackPublication.track != null }
                    }
                }
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
    } else {
        Box(modifier = modifier) {
            Icon(
                painter = painterResource(id = R.drawable.outline_videocam_off_24),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}