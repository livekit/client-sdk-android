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

import androidx.annotation.Keep
import io.livekit.android.util.LKLog
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Keep
@Serializable
data class AgentAttributes(
    @SerialName("lk.agent.inputs")
    val lkAgentInputs: List<AgentInput>? = null,

    @SerialName("lk.agent.outputs")
    val lkAgentOutputs: List<AgentOutput>? = null,

    @SerialName("lk.agent.state")
    val lkAgentState: AgentSdkState? = null,

    @SerialName("lk.publish_on_behalf")
    val lkPublishOnBehalf: String? = null,
)

@Keep
@Serializable(with = AgentInputSerializer::class)
enum class AgentInput(val value: String) {
    @SerialName("audio")
    Audio("audio"),

    @SerialName("text")
    Text("text"),

    @SerialName("video")
    Video("video"),

    @SerialName("unknown")
    Unknown("unknown");

    companion object {
        fun fromValue(value: String): AgentInput = when (value) {
            "audio" -> Audio
            "text" -> Text
            "video" -> Video
            else -> {
                LKLog.e { "Unknown agent input value: $value" }
                Unknown
            }
        }
    }
}

@Keep
internal object AgentInputSerializer : KSerializer<AgentInput> {
    // Serial names of descriptors should be unique, this is why we advise including app package in the name.
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("io.livekit.android.room.types.AgentInput", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: AgentInput) {
        encoder.encodeString(value.value)
    }

    override fun deserialize(decoder: Decoder): AgentInput {
        val string = decoder.decodeString()
        return AgentInput.fromValue(string)
    }
}

@Keep
@Serializable(with = AgentOutputSerializer::class)
enum class AgentOutput(val value: String) {
    @SerialName("audio")
    Audio("audio"),

    @SerialName("transcription")
    Transcription("transcription"),

    @SerialName("unknown")
    Unknown("unknown");

    companion object {
        fun fromValue(value: String): AgentOutput = when (value) {
            "audio" -> Audio
            "transcription" -> Transcription
            else -> {
                LKLog.e { "Unknown agent output value: $value" }
                Unknown
            }
        }
    }
}

@Keep
internal object AgentOutputSerializer : KSerializer<AgentOutput> {
    // Serial names of descriptors should be unique, this is why we advise including app package in the name.
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("io.livekit.android.room.types.AgentOutput", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: AgentOutput) {
        encoder.encodeString(value.value)
    }

    override fun deserialize(decoder: Decoder): AgentOutput {
        val string = decoder.decodeString()
        return AgentOutput.fromValue(string)
    }
}

// Renamed from AgentState to AgentSdkState to avoid naming conflicts elsewhere.
@Keep
@Serializable(with = AgentSdkStateSerializer::class)
enum class AgentSdkState(val value: String) {
    @SerialName("idle")
    Idle("idle"),

    @SerialName("initializing")
    Initializing("initializing"),

    @SerialName("listening")
    Listening("listening"),

    @SerialName("speaking")
    Speaking("speaking"),

    @SerialName("thinking")
    Thinking("thinking"),

    @SerialName("unknown")
    Unknown("unknown");

    companion object {
        fun fromValue(value: String): AgentSdkState = when (value) {
            "idle" -> Idle
            "initializing" -> Initializing
            "listening" -> Listening
            "speaking" -> Speaking
            "thinking" -> Thinking
            else -> {
                LKLog.e { "Unknown agent sdk state value: $value" }
                Unknown
            }
        }
    }
}

@Keep
internal object AgentSdkStateSerializer : KSerializer<AgentSdkState> {
    // Serial names of descriptors should be unique, this is why we advise including app package in the name.
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("io.livekit.android.room.types.AgentSdkState", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: AgentSdkState) {
        encoder.encodeString(value.value)
    }

    override fun deserialize(decoder: Decoder): AgentSdkState {
        val string = decoder.decodeString()
        return AgentSdkState.fromValue(string)
    }
}

/**
 * Schema for transcription-related attributes
 */
@Keep
@Serializable
data class TranscriptionAttributes(
    /**
     * The segment id of the transcription
     */
    @SerialName("lk.segment_id")
    val lkSegmentID: String? = null,

    /**
     * The associated track id of the transcription
     */
    @SerialName("lk.transcribed_track_id")
    val lkTranscribedTrackID: String? = null,

    /**
     * Whether the transcription is final
     */
    @SerialName("lk.transcription_final")
    val lkTranscriptionFinal: Boolean? = null,
)
