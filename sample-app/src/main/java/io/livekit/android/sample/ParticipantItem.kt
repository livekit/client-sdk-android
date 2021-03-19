package io.livekit.android.sample

import android.view.View
import com.github.ajalt.timberkt.Timber
import com.xwray.groupie.viewbinding.BindableItem
import com.xwray.groupie.viewbinding.GroupieViewHolder
import io.livekit.android.room.Room
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.RemoteVideoTrackPublication
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.room.track.VideoTrackPublication
import io.livekit.android.sample.databinding.ParticipantItemBinding

class ParticipantItem(
    val room: Room,
    val remoteParticipant: RemoteParticipant
) :
    BindableItem<ParticipantItemBinding>() {

    private var videoBound = false

    override fun initializeViewBinding(view: View): ParticipantItemBinding {
        val binding = ParticipantItemBinding.bind(view)
        room.initVideoRenderer(binding.renderer)
        return binding
    }

    override fun bind(viewBinding: ParticipantItemBinding, position: Int) {
        viewBinding.run {

            remoteParticipant.listener = object : RemoteParticipant.Listener {
                override fun onSubscribe(
                    videoTrack: RemoteVideoTrackPublication,
                    participant: RemoteParticipant
                ) {
                    val track = videoTrack.videoTrack
                    if (track != null) {
                        setupVideoIfNeeded(track, viewBinding)
                    }
                }
            }
            val existingTrack = getVideoTrack()
            if (existingTrack != null) {
                setupVideoIfNeeded(existingTrack, viewBinding)
            }
        }
    }

    private fun getVideoTrack(): VideoTrack? {
        return remoteParticipant
            .remoteVideoTracks
            .firstOrNull()
            .let { it as? VideoTrackPublication }
            ?.videoTrack
    }

    private fun setupVideoIfNeeded(videoTrack: VideoTrack, viewBinding: ParticipantItemBinding) {
        if (videoBound) {
            return
        }

        videoBound = true
        Timber.v { "adding renderer to $videoTrack" }
        videoTrack.addRenderer(viewBinding.renderer)
    }

    override fun unbind(viewHolder: GroupieViewHolder<ParticipantItemBinding>) {
        super.unbind(viewHolder)
        videoBound = false
    }

    override fun getLayout(): Int = R.layout.participant_item
}