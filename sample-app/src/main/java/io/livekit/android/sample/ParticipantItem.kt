package io.livekit.android.sample

import android.view.View
import com.github.ajalt.timberkt.Timber
import com.xwray.groupie.viewbinding.BindableItem
import com.xwray.groupie.viewbinding.GroupieViewHolder
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.ParticipantListener
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.*
import io.livekit.android.sample.databinding.ParticipantItemBinding

class ParticipantItem(
    val room: Room,
    val participant: Participant
) : BindableItem<ParticipantItemBinding>() {

    private var boundVideoTrack: VideoTrack? = null

    override fun initializeViewBinding(view: View): ParticipantItemBinding {
        val binding = ParticipantItemBinding.bind(view)
        room.initVideoRenderer(binding.renderer)
        return binding
    }

    override fun bind(viewBinding: ParticipantItemBinding, position: Int) {
        viewBinding.identityText.text = participant.identity
        participant.listener = object : ParticipantListener {
            override fun onTrackSubscribed(
                track: Track,
                publication: RemoteTrackPublication,
                participant: RemoteParticipant
            ) {
                if (track !is VideoTrack) return
                if (publication.source == Track.Source.CAMERA) {
                    setupVideoIfNeeded(track, viewBinding)
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
            setupVideoIfNeeded(existingTrack, viewBinding)
        }
    }

    private fun getVideoTrack(): VideoTrack? {
        return participant.getTrackPublication(Track.Source.CAMERA)?.track as? VideoTrack
    }

    internal fun setupVideoIfNeeded(videoTrack: VideoTrack, viewBinding: ParticipantItemBinding) {
        if (boundVideoTrack != null) {
            return
        }

        boundVideoTrack = videoTrack
        Timber.v { "adding renderer to $videoTrack" }
        videoTrack.addRenderer(viewBinding.renderer)
    }

    override fun unbind(viewHolder: GroupieViewHolder<ParticipantItemBinding>) {
        super.unbind(viewHolder)
        boundVideoTrack?.removeRenderer(viewHolder.binding.renderer)
        boundVideoTrack = null
    }

    override fun getLayout(): Int = R.layout.participant_item
}