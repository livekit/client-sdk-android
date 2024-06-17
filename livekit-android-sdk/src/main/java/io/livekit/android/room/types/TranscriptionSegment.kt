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

package io.livekit.android.room.types

import io.livekit.android.util.LKLog
import livekit.LivekitModels

data class TranscriptionSegment(
    val id: String,
    val text: String,
    val language: String,
    val startTime: Long,
    val endTime: Long,
    val final: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TranscriptionSegment

        return id == other.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }
}

/**
 * Merges new segments into the map. The key should correspond to the segment id.
 */
fun MutableMap<String, TranscriptionSegment>.mergeNewSegments(newSegments: Collection<TranscriptionSegment>) {
    for (segment in newSegments) {
        val existingSegment = get(segment.id)
        if (existingSegment?.final == true) {
            LKLog.d { "new segment for ${segment.id} overwriting final segment?" }
        }
        put(segment.id, segment)
    }
}

/**
 * @suppress
 */
fun LivekitModels.TranscriptionSegment.toSDKType() =
    TranscriptionSegment(
        id = id,
        text = text,
        language = language,
        startTime = startTime,
        endTime = endTime,
        final = final,
    )
