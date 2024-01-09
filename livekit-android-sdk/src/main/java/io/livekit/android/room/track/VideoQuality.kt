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

package io.livekit.android.room.track

import livekit.LivekitModels

enum class VideoQuality {
    LOW,
    MEDIUM,
    HIGH,
    ;

    fun toProto(): LivekitModels.VideoQuality {
        return when (this) {
            LOW -> LivekitModels.VideoQuality.LOW
            MEDIUM -> LivekitModels.VideoQuality.MEDIUM
            HIGH -> LivekitModels.VideoQuality.HIGH
        }
    }

    companion object {
        fun fromProto(quality: LivekitModels.VideoQuality): VideoQuality? {
            return when (quality) {
                LivekitModels.VideoQuality.LOW -> LOW
                LivekitModels.VideoQuality.MEDIUM -> MEDIUM
                LivekitModels.VideoQuality.HIGH -> HIGH
                LivekitModels.VideoQuality.OFF -> null
                LivekitModels.VideoQuality.UNRECOGNIZED -> null
            }
        }
    }
}
