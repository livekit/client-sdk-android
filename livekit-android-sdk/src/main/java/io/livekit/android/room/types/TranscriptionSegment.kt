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
import java.util.Date

data class TranscriptionSegment(
    /**
     * The id of the transcription segment.
     */
    val id: String,
    /**
     * The text of the transcription.
     */
    val text: String,
    /**
     * Language
     */
    val language: String,
    /**
     * If false, the user can expect this transcription to update in the future.
     */
    val final: Boolean,
    /**
     * When this client first locally received this segment.
     *
     * Defined as milliseconds from epoch date (using [Date.getTime])
     */
    val firstReceivedTime: Long = Date().time,
    /**
     * When this client last locally received this segment.
     *
     * Defined as milliseconds from epoch date (using [Date.getTime])
     */
    val lastReceivedTime: Long = Date().time,
) {
    override fun hashCode(): Int {
        return id.hashCode()
    }
}

/**
 * Merges [newSegment] info into this segment if the ids are equal.
 *
 * Returns `this` if a different segment is passed.
 */
fun TranscriptionSegment?.merge(newSegment: TranscriptionSegment): TranscriptionSegment {
    if (this == null) {
        return newSegment
    }

    if (this.id != newSegment.id) {
        return this
    }

    if (this.final) {
        LKLog.d { "new segment for $id overwriting final segment?" }
    }

    return copy(
        id = this.id,
        text = newSegment.text,
        language = newSegment.language,
        final = newSegment.final,
        firstReceivedTime = this.firstReceivedTime,
        lastReceivedTime = newSegment.lastReceivedTime,
    )
}

/**
 * Merges new segments into the map. The key should correspond to the segment id.
 */
fun MutableMap<String, TranscriptionSegment>.mergeNewSegments(newSegments: Collection<TranscriptionSegment>) {
    for (segment in newSegments) {
        val existingSegment = get(segment.id)
        put(segment.id, existingSegment.merge(segment))
    }
}

/**
 * @suppress
 */
fun LivekitModels.TranscriptionSegment.toSDKType(firstReceivedTime: Long = Date().time) =
    TranscriptionSegment(
        id = id,
        text = text,
        language = language,
        final = final,
        firstReceivedTime = firstReceivedTime,
    )
