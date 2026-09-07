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
 * Options for publishing a data track.
 *
 * @param frameFormat Describes the track's frames. Leaving this unset publishes an untyped track.
 */
data class DataTrackPublishOptions(
    val frameFormat: DataTrackFrameFormat? = null,
) {
    /**
     * Declares the frame format inline.
     *
     * @param frameEncoding Encoding of the track's frames.
     * @param schema Schema describing the track's frames.
     */
    constructor(
        frameEncoding: DataTrackFrameEncoding,
        schema: DataTrackSchemaId? = null,
    ) : this(DataTrackFrameFormat(frameEncoding, schema))
}

/**
 * Describes the frames on a data track.
 *
 * A schema always describes frames in a specific encoding, so [frameEncoding] is required
 * alongside a [schema]. The declared metadata is surfaced to subscribers via [DataTrackInfo].
 *
 * @param frameEncoding Encoding of the track's frames.
 * @param schema Schema describing the track's frames.
 */
data class DataTrackFrameFormat(
    val frameEncoding: DataTrackFrameEncoding,
    val schema: DataTrackSchemaId? = null,
)
