/*
 * Copyright 2026 LiveKit, Inc.
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

package io.livekit.android.room.datatrack

import io.livekit.uniffi.DataTrackSchemaId as FfiSchemaId
import uniffi.livekit_datatrack.DataTrackFrameEncoding as FfiFrameEncoding
import uniffi.livekit_datatrack.DataTrackSchemaEncoding as FfiSchemaEncoding

/**
 * Identifies the schema describing a data track's frames.
 *
 * @param name Schema name, unique within the room.
 * @param encoding Encoding of the schema definition itself.
 */
data class DataTrackSchemaId(
    val name: String,
    val encoding: DataTrackSchemaEncoding,
) {
    internal constructor(ffi: FfiSchemaId) : this(
        name = ffi.name,
        encoding = DataTrackSchemaEncoding.fromFfi(ffi.encoding),
    )

    internal fun toFfi(): FfiSchemaId = FfiSchemaId(
        name = name,
        encoding = encoding.toFfi(),
    )
}

/**
 * Encoding of a data track schema definition.
 *
 * Identifiers naming a well-known encoding always map to that case, so a custom encoding cannot
 * shadow one.
 */
sealed class DataTrackSchemaEncoding {
    /**
     * Stable string form. Identifiers naming a well-known encoding always map to that case.
     */
    abstract val identifier: String

    /** Protocol Buffers schema (`.proto`), describing `protobuf`-encoded frames. */
    data object Protobuf : DataTrackSchemaEncoding() {
        override val identifier: String = "protobuf"
    }

    /** FlatBuffers schema (`.fbs`), describing `flatbuffer`-encoded frames. */
    data object Flatbuffer : DataTrackSchemaEncoding() {
        override val identifier: String = "flatbuffer"
    }

    /** ROS 1 message definition, describing `ros1`-encoded frames. */
    data object Ros1Msg : DataTrackSchemaEncoding() {
        override val identifier: String = "ros1msg"
    }

    /** ROS 2 message definition, describing `cdr`-encoded frames. */
    data object Ros2Msg : DataTrackSchemaEncoding() {
        override val identifier: String = "ros2msg"
    }

    /** ROS 2 IDL definition, describing `cdr`-encoded frames. */
    data object Ros2Idl : DataTrackSchemaEncoding() {
        override val identifier: String = "ros2idl"
    }

    /** OMG IDL definition, describing `cdr`-encoded frames. */
    data object OmgIdl : DataTrackSchemaEncoding() {
        override val identifier: String = "omgidl"
    }

    /** JSON Schema, describing `json`-encoded frames. */
    data object JsonSchema : DataTrackSchemaEncoding() {
        override val identifier: String = "jsonschema"
    }

    /** Another well-known encoding not known to this client version. */
    data object Other : DataTrackSchemaEncoding() {
        override val identifier: String = "other"
    }

    /**
     * An application-specific encoding identified by [identifier].
     */
    data class Custom(override val identifier: String) : DataTrackSchemaEncoding()

    internal fun toFfi(): FfiSchemaEncoding = when (this) {
        Protobuf -> FfiSchemaEncoding.Protobuf
        Flatbuffer -> FfiSchemaEncoding.Flatbuffer
        Ros1Msg -> FfiSchemaEncoding.Ros1Msg
        Ros2Msg -> FfiSchemaEncoding.Ros2Msg
        Ros2Idl -> FfiSchemaEncoding.Ros2Idl
        OmgIdl -> FfiSchemaEncoding.OmgIdl
        JsonSchema -> FfiSchemaEncoding.JsonSchema
        Other -> FfiSchemaEncoding.Other
        is Custom -> FfiSchemaEncoding.Custom(identifier)
    }

    companion object {
        /**
         * Creates an encoding from its [identifier]; unrecognized identifiers become [Custom].
         */
        fun fromIdentifier(identifier: String): DataTrackSchemaEncoding = when (identifier) {
            "protobuf" -> Protobuf
            "flatbuffer" -> Flatbuffer
            "ros1msg" -> Ros1Msg
            "ros2msg" -> Ros2Msg
            "ros2idl" -> Ros2Idl
            "omgidl" -> OmgIdl
            "jsonschema" -> JsonSchema
            "other" -> Other
            else -> Custom(identifier)
        }

        internal fun fromFfi(ffi: FfiSchemaEncoding): DataTrackSchemaEncoding = when (ffi) {
            FfiSchemaEncoding.Protobuf -> Protobuf
            FfiSchemaEncoding.Flatbuffer -> Flatbuffer
            FfiSchemaEncoding.Ros1Msg -> Ros1Msg
            FfiSchemaEncoding.Ros2Msg -> Ros2Msg
            FfiSchemaEncoding.Ros2Idl -> Ros2Idl
            FfiSchemaEncoding.OmgIdl -> OmgIdl
            FfiSchemaEncoding.JsonSchema -> JsonSchema
            FfiSchemaEncoding.Other -> Other
            is FfiSchemaEncoding.Custom -> Custom(ffi.v1)
        }
    }
}

/**
 * Encoding of the frames sent over a data track.
 *
 * Identifiers naming a well-known encoding always map to that case, so a custom encoding cannot
 * shadow one.
 */
sealed class DataTrackFrameEncoding {
    /**
     * Stable string form. Identifiers naming a well-known encoding always map to that case.
     */
    abstract val identifier: String

    /** ROS 1. */
    data object Ros1 : DataTrackFrameEncoding() {
        override val identifier: String = "ros1"
    }

    /** CDR (ROS 2 / OMG IDL). */
    data object Cdr : DataTrackFrameEncoding() {
        override val identifier: String = "cdr"
    }

    /** Protocol Buffers. */
    data object Protobuf : DataTrackFrameEncoding() {
        override val identifier: String = "protobuf"
    }

    /** FlatBuffers. */
    data object Flatbuffer : DataTrackFrameEncoding() {
        override val identifier: String = "flatbuffer"
    }

    /** CBOR, self-describing. */
    data object Cbor : DataTrackFrameEncoding() {
        override val identifier: String = "cbor"
    }

    /** MessagePack, self-describing. */
    data object Msgpack : DataTrackFrameEncoding() {
        override val identifier: String = "msgpack"
    }

    /** JSON, self-describing. */
    data object Json : DataTrackFrameEncoding() {
        override val identifier: String = "json"
    }

    /** Another well-known encoding not known to this client version. */
    data object Other : DataTrackFrameEncoding() {
        override val identifier: String = "other"
    }

    /**
     * An application-specific encoding identified by [identifier].
     */
    data class Custom(override val identifier: String) : DataTrackFrameEncoding()

    internal fun toFfi(): FfiFrameEncoding = when (this) {
        Ros1 -> FfiFrameEncoding.Ros1
        Cdr -> FfiFrameEncoding.Cdr
        Protobuf -> FfiFrameEncoding.Protobuf
        Flatbuffer -> FfiFrameEncoding.Flatbuffer
        Cbor -> FfiFrameEncoding.Cbor
        Msgpack -> FfiFrameEncoding.Msgpack
        Json -> FfiFrameEncoding.Json
        Other -> FfiFrameEncoding.Other
        is Custom -> FfiFrameEncoding.Custom(identifier)
    }

    companion object {
        /**
         * Creates an encoding from its [identifier]; unrecognized identifiers become [Custom].
         */
        fun fromIdentifier(identifier: String): DataTrackFrameEncoding = when (identifier) {
            "ros1" -> Ros1
            "cdr" -> Cdr
            "protobuf" -> Protobuf
            "flatbuffer" -> Flatbuffer
            "cbor" -> Cbor
            "msgpack" -> Msgpack
            "json" -> Json
            "other" -> Other
            else -> Custom(identifier)
        }

        internal fun fromFfi(ffi: FfiFrameEncoding): DataTrackFrameEncoding = when (ffi) {
            FfiFrameEncoding.Ros1 -> Ros1
            FfiFrameEncoding.Cdr -> Cdr
            FfiFrameEncoding.Protobuf -> Protobuf
            FfiFrameEncoding.Flatbuffer -> Flatbuffer
            FfiFrameEncoding.Cbor -> Cbor
            FfiFrameEncoding.Msgpack -> Msgpack
            FfiFrameEncoding.Json -> Json
            FfiFrameEncoding.Other -> Other
            is FfiFrameEncoding.Custom -> Custom(ffi.v1)
        }
    }
}
