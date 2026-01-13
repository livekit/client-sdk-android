/*
 * Copyright 2025-2026 LiveKit, Inc.
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

internal fun AgentAttributes.Companion.fromJsonObject(jsonObject: JsonObject) =
    klaxon.parseFromJsonObject<AgentAttributes>(jsonObject)

/**
 * @suppress
 */
fun AgentAttributes.Companion.fromMap(map: Map<String, *>): AgentAttributes {
    return fromJsonObject(JsonObject(map)) ?: AgentAttributes()
}

/**
 * @suppress
 */
fun AgentAttributes.Companion.fromStringMap(map: Map<String, String>): AgentAttributes {
    val parseMap = mutableMapOf<String, Any?>()
    for ((key, converter) in AGENT_ATTRIBUTES_CONVERSION) {
        parseMap[key] = converter(map[key])
    }

    return fromMap(parseMap)
}

/**
 * Protobuf attribute maps are [String, String], so need to parse arrays/maps manually.
 * @suppress
 */
val AGENT_ATTRIBUTES_CONVERSION = mapOf<String, (String?) -> Any?>(
    "lk.agent.inputs" to { json -> json?.let { klaxon.parseArray<List<String>>(json) } },
    "lk.agent.outputs" to { json -> json?.let { klaxon.parseArray<List<String>>(json) } },
    "lk.agent.state" to { json -> json },
    "lk.publish_on_behalf" to { json -> json },
)

internal fun TranscriptionAttributes.Companion.fromJsonObject(jsonObject: JsonObject) =
    klaxon.parseFromJsonObject<TranscriptionAttributes>(jsonObject)

/**
 * @suppress
 */
fun TranscriptionAttributes.Companion.fromMap(map: Map<String, *>): TranscriptionAttributes {
    return fromJsonObject(JsonObject(map)) ?: TranscriptionAttributes()
}

/**
 * @suppress
 */
fun TranscriptionAttributes.Companion.fromStringMap(map: Map<String, String>): TranscriptionAttributes {
    val parseMap = mutableMapOf<String, Any?>()
    for ((key, converter) in TRANSCRIPTION_ATTRIBUTES_CONVERSION) {
        parseMap[key] = converter(map[key])
    }
    return fromMap(parseMap)
}

/**
 * Protobuf attribute maps are [String, String], so need to parse arrays/maps manually.
 * @suppress
 */
val TRANSCRIPTION_ATTRIBUTES_CONVERSION = mapOf<String, (String?) -> Any?>(
    "lk.segment_id" to { json -> json },
    "lk.transcribed_track_id" to { json -> json },
    "lk.transcription_final" to { json -> json?.let { klaxon.parse(json) } },
)
