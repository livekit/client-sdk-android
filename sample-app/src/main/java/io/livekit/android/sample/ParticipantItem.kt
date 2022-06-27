package io.livekit.android.sample

import android.view.View
import com.github.ajalt.timberkt.Timber
import com.xwray.groupie.viewbinding.BindableItem
import com.xwray.groupie.viewbinding.GroupieViewHolder
import io.livekit.android.room.Room
import io.livekit.android.room.participant.ConnectionQuality
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.sample.databinding.ParticipantItemBinding
import io.livekit.android.util.flow
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@OptIn(ExperimentalCoroutinesApi::class)
class ParticipantItem(
    private val room: Room,
    private val participant: Participant,
    private val speakerView: Boolean = false,
) : BindableItem<ParticipantItemBinding>() {

    private var boundVideoTrack: VideoTrack? = null
    private var coroutineScope: CoroutineScope? = null

    override fun initializeViewBinding(view: View): ParticipantItemBinding {
        val binding = ParticipantItemBinding.bind(view)
        room.initVideoRenderer(binding.renderer)
        return binding
    }

    private fun ensureCoroutineScope() {
        if (coroutineScope == null) {
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        }
    }

    override fun bind(viewBinding: ParticipantItemBinding, position: Int) {

        ensureCoroutineScope()
        coroutineScope?.launch {
            participant::identity.flow.collect { identity ->
                viewBinding.identityText.text = identity
            }
        }
        coroutineScope?.launch {
            participant::audioTracks.flow
                .flatMapLatest { tracks ->
                    val audioTrack = tracks.firstOrNull()?.first
                    if (audioTrack != null) {
                        audioTrack::muted.flow
                    } else {
                        flowOf(true)
                    }
                }
                .collect { muted ->
                    viewBinding.muteIndicator.visibility = if (muted) View.VISIBLE else View.INVISIBLE
                }
        }
        coroutineScope?.launch {
            participant::connectionQuality.flow
                .collect { quality ->
                    viewBinding.connectionQuality.visibility =
                        if (quality == ConnectionQuality.POOR) View.VISIBLE else View.INVISIBLE
                }
        }

        // observe videoTracks changes.
        val videoTrackPubFlow = participant::videoTracks.flow
            .map { participant to it }
            .flatMapLatest { (participant, videoTracks) ->
                // Prioritize any screenshare streams.
                val trackPublication = participant.getTrackPublication(Track.Source.SCREEN_SHARE)
                    ?: participant.getTrackPublication(Track.Source.CAMERA)
                    ?: videoTracks.firstOrNull()?.first

                flowOf(trackPublication)
            }

        coroutineScope?.launch {
            videoTrackPubFlow
                .flatMapLatest { pub ->
                    if (pub != null) {
                        pub::track.flow
                    } else {
                        flowOf(null)
                    }
                }
                .collectLatest { videoTrack ->
                    setupVideoIfNeeded(videoTrack as? VideoTrack, viewBinding)
                }
        }
        coroutineScope?.launch {
            videoTrackPubFlow
                .flatMapLatest { pub ->
                    if (pub != null) {
                        pub::muted.flow
                    } else {
                        flowOf(true)
                    }
                }
                .collectLatest { muted ->
                    viewBinding.renderer.visibleOrInvisible(!muted)
                }
        }
        val existingTrack = getVideoTrack()
        if (existingTrack != null) {
            setupVideoIfNeeded(existingTrack, viewBinding)
        }
    }

    private fun getVideoTrack(): VideoTrack? {
        return participant.getTrackPublication(Track.Source.CAMERA)?.track as? VideoTrack
    }

    private fun setupVideoIfNeeded(videoTrack: VideoTrack?, viewBinding: ParticipantItemBinding) {
        if (boundVideoTrack == videoTrack) {
            return
        }
        boundVideoTrack?.removeRenderer(viewBinding.renderer)
        boundVideoTrack = videoTrack
        Timber.v { "adding renderer to $videoTrack" }
        videoTrack?.addRenderer(viewBinding.renderer)
    }

    override fun unbind(viewHolder: GroupieViewHolder<ParticipantItemBinding>) {
        coroutineScope?.cancel()
        coroutineScope = null
        super.unbind(viewHolder)
        boundVideoTrack?.removeRenderer(viewHolder.binding.renderer)
        boundVideoTrack = null
    }

    override fun getLayout(): Int =
        if (speakerView)
            R.layout.speaker_view
        else
            R.layout.participant_item
}

private fun View.visibleOrGone(visible: Boolean) {
    visibility = if (visible) {
        View.VISIBLE
    } else {
        View.GONE
    }
}

private fun View.visibleOrInvisible(visible: Boolean) {
    visibility = if (visible) {
        View.VISIBLE
    } else {
        View.INVISIBLE
    }
}