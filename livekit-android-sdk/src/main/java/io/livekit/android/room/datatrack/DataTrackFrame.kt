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

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import io.livekit.uniffi.DataTrackFrame as FfiDataTrackFrame

/**
 * A single unit of application data sent or received over a data track.
 *
 * @param payload The application payload carried by this frame.
 * @param userTimestamp Optional sender-provided timestamp, opaque to the SDK and carried
 * end-to-end unmodified. Publisher and subscriber agree on what it means, so a sensor's clock
 * works as well as wall time. [now] and [durationSinceTimestamp] are the exception — they
 * read it as milliseconds since the Unix epoch.
 */
class DataTrackFrame(
    val payload: ByteArray,
    val userTimestamp: Long? = null,
) {
    /**
     * How long ago the frame was stamped, or `null` if it carries no timestamp or the timestamp
     * lies in the future.
     *
     * Assumes [userTimestamp] is a Unix timestamp in milliseconds, as set by [now].
     */
    val durationSinceTimestamp: Duration?
        get() {
            val timestamp = userTimestamp ?: return null
            val elapsed = System.currentTimeMillis() - timestamp
            return elapsed.takeIf { it >= 0 }?.milliseconds
        }

    internal constructor(ffi: FfiDataTrackFrame) : this(
        payload = ffi.payload,
        userTimestamp = ffi.userTimestamp?.toLong(),
    )

    internal fun toFfi(): FfiDataTrackFrame = FfiDataTrackFrame(
        payload = payload,
        userTimestamp = userTimestamp?.toULong(),
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DataTrackFrame) return false
        return payload.contentEquals(other.payload) && userTimestamp == other.userTimestamp
    }

    override fun hashCode(): Int {
        var result = payload.contentHashCode()
        result = 31 * result + (userTimestamp?.hashCode() ?: 0)
        return result
    }

    companion object {
        /**
         * Creates a frame stamped with the current time, in milliseconds since the Unix epoch.
         */
        @JvmStatic
        fun now(payload: ByteArray): DataTrackFrame {
            return DataTrackFrame(payload, System.currentTimeMillis())
        }
    }
}
