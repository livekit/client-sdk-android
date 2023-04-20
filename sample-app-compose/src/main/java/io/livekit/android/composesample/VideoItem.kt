package io.livekit.android.composesample

import androidx.compose.foundation.layout.Box
import androidx.compose.material.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import io.livekit.android.compose.VideoRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.util.flow

/**
 * This widget primarily serves as a way to observe changes in [Participant.videoTracks].
 */
@Composable
fun VideoItemTrackSelector(
    room: Room,
    participant: Participant,
    modifier: Modifier = Modifier,
    mirror: Boolean = false,
) {
    val videoTrackMap by participant::videoTracks.flow.collectAsState(initial = emptyList())
    val videoPubs = videoTrackMap.filter { (pub) -> pub.subscribed }
        .map { (pub) -> pub }

    // Find the most appropriate video stream to show
    // Prioritize screen share, then camera, then any video stream.
    val videoPub = videoPubs.firstOrNull { pub -> pub.source == Track.Source.SCREEN_SHARE }
        ?: videoPubs.firstOrNull { pub -> pub.source == Track.Source.CAMERA }
        ?: videoPubs.firstOrNull()

    val videoTrack = videoPub?.track as? VideoTrack
    val videoMuted by
    if (videoPub != null) {
        videoPub::muted.flow.collectAsState()
    } else {
        remember(videoPub) {
            derivedStateOf { false }
        }
    }

    if (videoTrack != null && !videoMuted) {
        VideoRenderer(
            room = room,
            videoTrack = videoTrack,
            mirror = mirror,
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