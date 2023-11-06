/*
 * Copyright 2023 LiveKit, Inc.
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

package io.livekit.android.room.track.video

data class ScalabilityMode(val spatial: Int, val temporal: Int, val suffix: String) {
    companion object {
        private val REGEX = """L(\d)T(\d)(h|_KEY|_KEY_SHIFT)?""".toRegex()
        fun parseFromString(mode: String): ScalabilityMode {
            val match = REGEX.matchEntire(mode) ?: throw IllegalArgumentException("can't parse scalability mode: $mode")
            val (spatial, temporal, suffix) = match.destructured

            return ScalabilityMode(spatial.toInt(), temporal.toInt(), suffix)
        }
    }
}
