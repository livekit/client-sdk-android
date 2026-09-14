/*
 * Copyright 2026 LiveKit, Inc.
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

package io.livekit.android.room.datatrack

/**
 * Events emitted by [IncomingDataTrackManager] when the UniFFI remote manager reports
 * publication changes.
 *
 * @suppress
 */
internal sealed class IncomingDataTrackEvent {
    /**
     * A remote data track is available to subscribe. The publisher may not be in the room yet.
     */
    class TrackPublished(val track: RemoteDataTrack) : IncomingDataTrackEvent()

    /**
     * A remote data track with [sid] is no longer published.
     */
    class TrackUnpublished(
        val sid: DataTrackSid,
        val track: RemoteDataTrack,
    ) : IncomingDataTrackEvent()
}
