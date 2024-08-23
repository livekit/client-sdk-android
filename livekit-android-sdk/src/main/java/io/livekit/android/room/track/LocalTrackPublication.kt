/*
 * Copyright 2023-2024 LiveKit, Inc.
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

package io.livekit.android.room.track

import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.TrackPublishOptions
import livekit.LivekitModels

class LocalTrackPublication(
    info: LivekitModels.TrackInfo,
    track: Track,
    participant: LocalParticipant,
    val options: TrackPublishOptions,
) : TrackPublication(info, track, participant) {

    /**
     * Mute or unmute the current track. Muting the track would stop audio or video from being
     * transmitted to the server, and notify other participants in the room.
     */
    override var muted: Boolean
        get() = super.muted
        public set(muted) {
            if (muted == this.muted) {
                return
            }

            val mediaTrack = track ?: return

            mediaTrack.enabled = !muted
            super.muted = muted

            // send updates to server
            val participant = this.participant.get() as? LocalParticipant ?: return

            participant.engine.updateMuteStatus(sid, muted)

            if (muted) {
                participant.onTrackMuted(this)
            } else {
                participant.onTrackUnmuted(this)
            }
        }
}
