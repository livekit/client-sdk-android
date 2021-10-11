package io.livekit.android.composesample

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.github.ajalt.timberkt.Timber
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.participant.ParticipantListener
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.RemoteTrackPublication
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack

@Composable
fun ParticipantItem(
    room: Room,
    participant: RemoteParticipant,
) {
    var videoBound by remember(room, participant) { mutableStateOf(false) }
    fun getVideoTrack(): VideoTrack? {
        return participant
            .videoTracks.values
            .firstOrNull()?.track as? VideoTrack
    }

    fun setupVideoIfNeeded(videoTrack: VideoTrack, view: TextureViewRenderer) {
        if (videoBound) {
            return
        }

        videoBound = true
        Timber.v { "adding renderer to $videoTrack" }
        videoTrack.addRenderer(view)
    }

    AndroidView(
        factory = { context ->
            TextureViewRenderer(context).apply {
                room.initVideoRenderer(this)
            }
        },
        modifier = Modifier.fillMaxSize(),
        update = { view ->
            participant.listener = object : ParticipantListener {
                override fun onTrackSubscribed(
                    track: Track,
                    publication: RemoteTrackPublication,
                    participant: RemoteParticipant
                ) {
                    if (track is VideoTrack) {
                        setupVideoIfNeeded(track, view)
                    }
                }

                override fun onTrackUnpublished(
                    publication: RemoteTrackPublication,
                    participant: RemoteParticipant
                ) {
                    super.onTrackUnpublished(publication, participant)
                    Timber.e { "Track unpublished" }
                }
            }
            val existingTrack = getVideoTrack()
            if (existingTrack != null) {
                setupVideoIfNeeded(existingTrack, view)
            }
        }
    )
}