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

package io.livekit.android.room.types

import com.beust.klaxon.JsonObject

// AgentTypes.kt is a generated file and should not be edited.
// Add any required functions through extensions here.

fun AgentAttributes.Companion.fromJsonObject(jsonObject: JsonObject) =
    klaxon.parseFromJsonObject<AgentAttributes>(jsonObject)

fun AgentAttributes.Companion.fromMap(map: Map<String, *>): AgentAttributes {
    val jsonObject = JsonObject(map)
    return fromJsonObject(jsonObject)!!
}

fun TranscriptionAttributes.Companion.fromJsonObject(jsonObject: JsonObject) =
    klaxon.parseFromJsonObject<TranscriptionAttributes>(jsonObject)

fun TranscriptionAttributes.Companion.fromMap(map: Map<String, *>): TranscriptionAttributes {
    var map = map
    val transcriptionFinal = map["lk.transcription_final"]
    if (transcriptionFinal !is Boolean) {
        map = map.toMutableMap()
        map["lk.transcription_final"] = transcriptionFinal?.toString()?.toBooleanStrictOrNull()
    }
    val jsonObject = JsonObject(map)

    return fromJsonObject(jsonObject)!!
}
