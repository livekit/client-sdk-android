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

package io.livekit.android.events

import io.livekit.android.room.track.TrackPublication
import io.livekit.android.room.types.TranscriptionSegment

sealed class TrackPublicationEvent(val publication: TrackPublication) : Event() {
    class TranscriptionReceived(
        /**
         * The applicable track publication these transcriptions apply to.
         */
        publication: TrackPublication,
        /**
         * The transcription segments.
         */
        val transcriptions: List<TranscriptionSegment>,
    ) : TrackPublicationEvent(publication)
}
