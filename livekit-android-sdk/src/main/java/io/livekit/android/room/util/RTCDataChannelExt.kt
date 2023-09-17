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

package io.livekit.android.room.util

import org.webrtc.DataChannel

fun DataChannel.unpackedTrackLabel(): Triple<String, String, String> {
    val parts = label().split("|")
    val participantSid: String
    val trackSid: String
    val name: String

    if (parts.count() == 3) {
        participantSid = parts[0]
        trackSid = parts[1]
        name = parts[2]
    } else {
        participantSid = ""
        trackSid = ""
        name = ""
    }

    return Triple(participantSid, trackSid, name)
}
