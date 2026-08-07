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

package io.livekit.android.room.datastream

sealed class StreamException(message: String? = null) : Exception(message) {
    /**
     * Unable to open a stream with the same ID as an existing open stream.
     */
    class AlreadyOpenedException : StreamException()

    /**
     * Stream closed abnormally by remote participant.
     */
    class AbnormalEndException(message: String? = null) : StreamException(message)

    /**
     * Incoming chunk data could not be decoded.
     *
     * Covers both invalid UTF-8 in a text stream and a compressed stream that could not be
     * decompressed.
     */
    class DecodeFailedException(message: String? = null) : StreamException(message)

    /**
     * Length exceeded total length specified in stream header.
     */
    open class LengthExceededException(message: String? = null) : StreamException(message)

    /**
     * A stream header was too large to send.
     *
     * A header travels in a single packet, so its attributes, topic and framing together have to
     * fit the packet budget. Raised in place of sending an oversized header.
     *
     * A subclass of [LengthExceededException] so that code catching that keeps catching every
     * size-limit failure.
     */
    class HeaderTooLargeException(message: String? = null) : LengthExceededException(message)

    /**
     * An incoming stream's payload exceeded the maximum accepted size.
     *
     * @see io.livekit.android.room.datastream.DataStreamOptions.maxPayloadSize
     */
    class PayloadTooLargeException(message: String? = null) : LengthExceededException(message)

    /**
     * Length is less than total length specified in stream header.
     */
    class IncompleteException : StreamException()

    /**
     * Stream terminated before completion.
     *
     * [reason] distinguishes why, for the cases that do not have a dedicated exception.
     */
    class TerminatedException
    @JvmOverloads
    constructor(
        message: String? = null,
        val reason: Reason = Reason.UNKNOWN,
    ) : StreamException(message) {
        enum class Reason {
            /** No specific reason was reported. */
            UNKNOWN,

            /** The stream had already been closed. */
            ALREADY_CLOSED,

            /** An incoming header could not be understood. */
            INVALID_HEADER,

            /** A chunk arrived out of order, leaving a gap the stream cannot recover from. */
            MISSED_CHUNK,

            /** A packet could not be handed to the transport. */
            SEND_FAILED,

            /** A file name was not a plain name, or tried to escape its directory. */
            INVALID_FILE_NAME,

            /** Reading or writing the underlying file failed. */
            IO,
        }
    }

    /**
     * Cannot perform operations on an unknown stream.
     */
    class UnknownStreamException : StreamException()

    /**
     * Given destination URL is not a directory.
     */
    class NotDirectoryException : StreamException()

    /**
     * Unable to read information about the file to send.
     */
    class FileInfoUnavailableException : StreamException()

    /**
     * Encryption of the data chunks did not match the declared encryption type.
     */
    class EncryptionTypeMismatch(message: String? = null) : StreamException(message)

    /**
     * A stream failed for a reason internal to the SDK, with no more specific cause available.
     */
    class InternalException(message: String? = null) : StreamException(message)
}
