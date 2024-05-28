/*
 * Copyright 2024 LiveKit, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:OptIn(ExperimentalCoroutinesApi::class)

package io.livekit.android.sample

import android.view.View
import com.github.ajalt.timberkt.Timber
import com.xwray.groupie.viewbinding.BindableItem
import com.xwray.groupie.viewbinding.GroupieViewHolder
import io.livekit.android.room.Room
import io.livekit.android.room.participant.ConnectionQuality
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.sample.databinding.ParticipantItemBinding
import io.livekit.android.util.flow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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
                viewBinding.identityText.text = identity?.value
            }
        }
        coroutineScope?.launch {
            participant::isSpeaking.flow.collect { isSpeaking ->
                if (isSpeaking) {
                    showFocus(viewBinding)
                } else {
                    hideFocus(viewBinding)
                }
            }
        }
        coroutineScope?.launch {
            participant::audioTrackPublications.flow
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
        val videoTrackPubFlow = participant::videoTrackPublications.flow
            .map { participant to it }
            .flatMapLatest { (participant, videoTracks) ->
                // Prioritize any screenshare streams.
                val trackPublication = participant.getTrackPublication(Track.Source.SCREEN_SHARE)
                    ?: participant.getTrackPublication(Track.Source.CAMERA)
                    ?: videoTracks.firstOrNull()?.first

                flowOf(trackPublication)
            }

        coroutineScope?.launch {
            val videoTrackFlow = videoTrackPubFlow
                .flatMapLatestOrNull { pub -> pub::track.flow }

            // Configure video view with track
            launch {
                videoTrackFlow.collectLatest { videoTrack ->
                    setupVideoIfNeeded(videoTrack as? VideoTrack, viewBinding)
                }
            }

            // For local participants, mirror camera if using front camera.
            if (participant == room.localParticipant) {
                launch {
                    videoTrackFlow
                        .flatMapLatestOrNull { track -> (track as LocalVideoTrack)::options.flow }
                        .collectLatest { options ->
                            viewBinding.renderer.setMirror(options?.position == CameraPosition.FRONT)
                        }
                }
            }
        }

        // Handle muted changes
        coroutineScope?.launch {
            videoTrackPubFlow
                .flatMapLatestOrNull { pub -> pub::muted.flow }
                .collectLatest { muted ->
                    viewBinding.renderer.visibleOrInvisible(!(muted ?: true))
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
        if (speakerView) {
            R.layout.speaker_view
        } else {
            R.layout.participant_item
        }
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

private fun showFocus(binding: ParticipantItemBinding) {
    binding.speakingIndicator.visibility = View.VISIBLE
}

private fun hideFocus(binding: ParticipantItemBinding) {
    binding.speakingIndicator.visibility = View.INVISIBLE
}

private inline fun <T, R> Flow<T?>.flatMapLatestOrNull(
    crossinline transform: suspend (value: T) -> Flow<R>,
): Flow<R?> {
    return flatMapLatest {
        if (it == null) {
            flowOf(null)
        } else {
            transform(it)
        }
    }
}
