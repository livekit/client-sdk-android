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

package io.livekit.android.room.track

import livekit.LivekitRtc

/**
 * Proactive FlexFEC protection the server adds to a subscribed video track.
 *
 * The server sends repair packets alongside the track's media, so the receiver can recover lost
 * packets without waiting for a retransmission.
 *
 * @see RemoteTrackPublication.setFecProtection
 */
sealed class FecProtection {
    /** No repair packets. This is the default. */
    data object Disabled : FecProtection()

    /**
     * Protection follows the observed packet loss, using the same algorithm as libwebrtc.
     */
    data object Adaptive : FecProtection()

    /**
     * Constant protection at [level], regardless of packet loss.
     */
    data class Fixed(val level: FecProtectionLevel) : FecProtection()

    /**
     * Protection never drops below [level]. It rises adaptively under higher packet loss, and
     * returns to [level] once the loss subsides.
     */
    data class Minimum(val level: FecProtectionLevel) : FecProtection()

    internal fun toProto(): LivekitRtc.FECProtection {
        val builder = LivekitRtc.FECProtection.newBuilder()
        when (this) {
            Disabled -> builder.mode = LivekitRtc.FECProtectionMode.FEC_MODE_DISABLED
            Adaptive -> builder.mode = LivekitRtc.FECProtectionMode.FEC_MODE_ADAPTIVE
            is Fixed -> {
                builder.mode = LivekitRtc.FECProtectionMode.FEC_MODE_FIXED
                builder.level = level.toProto()
            }

            is Minimum -> {
                builder.mode = LivekitRtc.FECProtectionMode.FEC_MODE_MINIMUM
                builder.level = level.toProto()
            }
        }
        return builder.build()
    }
}

/**
 * The share of packets spent on FlexFEC repairs, used by [FecProtection.Fixed] and
 * [FecProtection.Minimum].
 *
 * Frames that span only a few packets get proportionally more repairs.
 */
enum class FecProtectionLevel {
    /** Approximately 15% repair packet overhead. */
    LOW,

    /** Approximately 25% repair packet overhead. */
    MEDIUM,

    /** Approximately 35% repair packet overhead. */
    HIGH,
    ;

    internal fun toProto(): LivekitRtc.FECProtectionLevel {
        return when (this) {
            LOW -> LivekitRtc.FECProtectionLevel.FEC_LEVEL_LOW
            MEDIUM -> LivekitRtc.FECProtectionLevel.FEC_LEVEL_MEDIUM
            HIGH -> LivekitRtc.FECProtectionLevel.FEC_LEVEL_HIGH
        }
    }
}
