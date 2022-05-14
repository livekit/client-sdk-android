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
import kotlinx.coroutines.flow.*

/**
 * This widget primarily serves as a way to observe changes in [videoTracks].
 */
@Composable
fun VideoItemTrackSelector(
    room: Room,
    participant: Participant,
    modifier: Modifier = Modifier
) {
    val videoTrackMap by participant::videoTracks.flow.collectAsState(initial = emptyList())
    val videoPubs = videoTrackMap.filter { (pub) -> pub.subscribed }
        .map { (pub) -> pub }
    val videoTrack = videoPubs.firstOrNull { pub -> pub.source == Track.Source.SCREEN_SHARE }?.track as? VideoTrack
        ?: videoPubs.firstOrNull { pub -> pub.source == Track.Source.CAMERA }?.track as? VideoTrack
        ?: videoPubs.firstOrNull()?.track as? VideoTrack

    if (videoTrack != null) {
        VideoRenderer(
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