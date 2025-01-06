/*
 * Copyright 2025 LiveKit, Inc.
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

package io.livekit.android.util

import okio.ByteString.Companion.encode

internal fun String?.byteLength(): Int {
    if (this == null) {
        return 0
    }
    return this.encode(Charsets.UTF_8).size
}

internal fun String.truncateBytes(maxBytes: Int): String {
    if (this.byteLength() <= maxBytes) {
        return this
    }

    var low = 0
    var high = length

    // Binary search for string that fits.
    while (low < high) {
        val mid = (low + high + 1) / 2
        if (this.substring(0, mid).byteLength() <= maxBytes) {
            low = mid
        } else {
            high = mid - 1
        }
    }

    return substring(0, low)
}
