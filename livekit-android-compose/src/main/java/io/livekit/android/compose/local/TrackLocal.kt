package io.livekit.android.compose.local

import androidx.compose.runtime.*
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.TrackPublication
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.util.flow
import kotlinx.coroutines.flow.collectLatest

/**
 *
 */
val TrackLocal =
    compositionLocalOf<Track> { throw IllegalStateException("No Track object available. This should only be used within a TrackScope.") }

@Composable
fun TrackScope(
    track: Track,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        TrackLocal provides track,
        content = content,
    )
}

/**
 * This widget primarily serves as a way to observe changes in [Participant.videoTracks].
 *
 * @param participant The participant to grab video publications from
 * @param sources The priority order of [Track.Source] to search for. Pass an empty list to bypass this.
 * @param predicate The
 */
@Composable
fun rememberVideoTrackPublication(
    participant: Participant,
    sources: List<Track.Source> = listOf(Track.Source.SCREEN_SHARE, Track.Source.CAMERA),
    predicate: (TrackPublication) -> Boolean = { false }
): State<TrackPublication?> {
    val trackPubState = remember { mutableStateOf<TrackPublication?>(null) }

    LaunchedEffect(participant) {
        participant::videoTracks.flow.collectLatest { videoTrackMap ->
            val videoPubs = videoTrackMap.filter { (pub) -> pub.subscribed }
                .map { (pub) -> pub }

            val videoPub = run {
                val predicates = sources.map { source -> { pub: TrackPublication -> pub.source == source } }
                    .plus { pub -> predicate(pub) }
                    .plus { true } // return first available video track.

                for (p in predicates) {
                    val pub = videoPubs.firstOrNull(p)
                    if (pub != null) {
                        return@run pub
                    }
                }

                return@run null
            }
            trackPubState.value = videoPub
        }
    }

    return trackPubState
}

@Composable
fun rememberVideoTrack(videoPub: TrackPublication?) {
    val trackState = remember { mutableStateOf<VideoTrack?>(null) }

    LaunchedEffect(videoPub) {
        if (videoPub == null) {
            trackState.value = null
        } else {
            videoPub::track.flow.collectLatest { track ->
                trackState.value = track as? VideoTrack
            }
        }
    }
}