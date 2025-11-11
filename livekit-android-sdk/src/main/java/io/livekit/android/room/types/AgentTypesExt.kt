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
    val jsonObject = JsonObject(map)
    return fromJsonObject(jsonObject)!!
}