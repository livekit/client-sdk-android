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

import android.annotation.SuppressLint
import androidx.annotation.Keep
import com.beust.klaxon.Converter
import com.beust.klaxon.Json
import com.beust.klaxon.JsonValue
import com.beust.klaxon.Klaxon
import kotlinx.serialization.Serializable

private fun <T> Klaxon.convert(k: kotlin.reflect.KClass<*>, fromJson: (JsonValue) -> T, toJson: (T) -> String, isUnion: Boolean = false) =
    this.converter(
        object : Converter {
            @Suppress("UNCHECKED_CAST")
            override fun toJson(value: Any) = toJson(value as T)
            override fun fromJson(jv: JsonValue) = fromJson(jv) as Any
            override fun canConvert(cls: Class<*>) = cls == k.java || (isUnion && cls.superclass == k.java)
        },
    )

internal val klaxon = Klaxon()
    .convert(AgentInput::class, { AgentInput.fromValue(it.string!!) }, { "\"${it.value}\"" })
    .convert(AgentOutput::class, { AgentOutput.fromValue(it.string!!) }, { "\"${it.value}\"" })
    .convert(AgentSdkState::class, { AgentSdkState.fromValue(it.string!!) }, { "\"${it.value}\"" })

@Keep
data class AgentAttributes(
    @Json(name = "lk.agent.inputs")
    val lkAgentInputs: List<AgentInput>? = null,

    @Json(name = "lk.agent.outputs")
    val lkAgentOutputs: List<AgentOutput>? = null,

    @Json(name = "lk.agent.state")
    val lkAgentState: AgentSdkState? = null,

    @Json(name = "lk.publish_on_behalf")
    val lkPublishOnBehalf: String? = null,
) {
    fun toJson() = klaxon.toJsonString(this)

    companion object {
        fun fromJson(json: String) = klaxon.parse<AgentAttributes>(json)
    }
}

@Keep
enum class AgentInput(val value: String) {
    Audio("audio"),
    Text("text"),
    Video("video");

    companion object {
        fun fromValue(value: String): AgentInput = when (value) {
            "audio" -> Audio
            "text" -> Text
            "video" -> Video
            else -> throw IllegalArgumentException()
        }
    }
}

@Keep
enum class AgentOutput(val value: String) {
    Audio("audio"),
    Transcription("transcription");

    companion object {
        fun fromValue(value: String): AgentOutput = when (value) {
            "audio" -> Audio
            "transcription" -> Transcription
            else -> throw IllegalArgumentException()
        }
    }
}

// Renamed from AgentState to AgentSdkState to avoid naming conflicts elsewhere.
@Keep
enum class AgentSdkState(val value: String) {
    Idle("idle"),
    Initializing("initializing"),
    Listening("listening"),
    Speaking("speaking"),
    Thinking("thinking");

    companion object {
        fun fromValue(value: String): AgentSdkState = when (value) {
            "idle" -> Idle
            "initializing" -> Initializing
            "listening" -> Listening
            "speaking" -> Speaking
            "thinking" -> Thinking
            else -> throw IllegalArgumentException()
        }
    }
}

/**
 * Schema for transcription-related attributes
 */
@Keep
@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class TranscriptionAttributes(
    /**
     * The segment id of the transcription
     */
    @Json(name = "lk.segment_id")
    val lkSegmentID: String? = null,

    /**
     * The associated track id of the transcription
     */
    @Json(name = "lk.transcribed_track_id")
    val lkTranscribedTrackID: String? = null,

    /**
     * Whether the transcription is final
     */
    @Json(name = "lk.transcription_final")
    val lkTranscriptionFinal: Boolean? = null,
) {
    fun toJson() = klaxon.toJsonString(this)

    companion object {
        fun fromJson(json: String) = klaxon.parse<TranscriptionAttributes>(json)
    }
}
