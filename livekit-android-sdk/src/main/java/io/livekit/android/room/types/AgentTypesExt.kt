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

import androidx.annotation.VisibleForTesting
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

// AgentTypes.kt is a generated file and should not be edited.
// Add any required functions through extensions here.
private val jsonSerializer = Json {
    allowStructuredMapKeys = true
    coerceInputValues = true
}
internal fun AgentAttributes.Companion.fromJsonObject(jsonObject: JsonObject) =
    jsonSerializer.decodeFromJsonElement<AgentAttributes>(jsonObject)

/**
 * @suppress
 */
fun AgentAttributes.Companion.fromMap(map: Map<String, JsonElement>): AgentAttributes {
    if (map.values.none()) {
        return AgentAttributes()
    }

    return fromJsonObject(JsonObject(map))
}

/**
 * @suppress
 */
fun AgentAttributes.Companion.fromStringMap(map: Map<String, String>): AgentAttributes {
    val parseMap = mutableMapOf<String, JsonElement>()
    for ((key, converter) in AGENT_ATTRIBUTES_CONVERSION) {
        converter(map[key])?.let { converted ->
            parseMap[key] = converted
        }
    }

    return fromMap(parseMap)
}

/**
 * Protobuf attribute maps are [String, String], so need to parse arrays/maps manually.
 * @suppress
 */
@VisibleForTesting
val AGENT_ATTRIBUTES_CONVERSION = mapOf<String, (String?) -> JsonElement?>(
    "lk.agent.inputs" to { json -> json?.let { jsonSerializer.decodeFromString<JsonArray>(json) } },
    "lk.agent.outputs" to { json -> json?.let { jsonSerializer.decodeFromString<JsonArray>(json) } },
    "lk.agent.state" to { json -> JsonPrimitive(json) },
    "lk.publish_on_behalf" to { json -> JsonPrimitive(json) },
)

internal fun TranscriptionAttributes.Companion.fromJsonObject(jsonObject: JsonObject) =
    jsonSerializer.decodeFromJsonElement<TranscriptionAttributes>(jsonObject)

/**
 * @suppress
 */
fun TranscriptionAttributes.Companion.fromMap(map: Map<String, JsonElement>): TranscriptionAttributes {
    if (map.values.none()) {
        return TranscriptionAttributes()
    }

    return fromJsonObject(JsonObject(map))
}

/**
 * @suppress
 */
fun TranscriptionAttributes.Companion.fromStringMap(map: Map<String, String>): TranscriptionAttributes {
    val parseMap = mutableMapOf<String, JsonElement>()
    for ((key, converter) in TRANSCRIPTION_ATTRIBUTES_CONVERSION) {
        converter(map[key])?.let { converted ->
            parseMap[key] = converted
        }
    }

    return fromMap(parseMap)
}

/**
 * Protobuf attribute maps are [String, String], so need to parse arrays/maps manually.
 * @suppress
 */
@VisibleForTesting
val TRANSCRIPTION_ATTRIBUTES_CONVERSION = mapOf<String, (String?) -> JsonElement?>(
    "lk.segment_id" to { json -> JsonPrimitive(json) },
    "lk.transcribed_track_id" to { json -> JsonPrimitive(json) },
    "lk.transcription_final" to { json -> json?.let { jsonSerializer.decodeFromString<JsonArray>(json) } },
)
