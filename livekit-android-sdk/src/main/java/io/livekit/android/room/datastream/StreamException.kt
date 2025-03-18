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

package io.livekit.android.room.datastream

sealed class StreamException(message: String? = null) : Exception(message) {
    class AlreadyOpenedException : StreamException()
    class AbnormalEndException(message: String?) : StreamException(message)
    class DecodeFailedException : StreamException()
    class LengthExceededException : StreamException()
    class IncompleteException : StreamException()
    class TerminatedException : StreamException()
    class UnknownStreamException : StreamException()
    class NotDirectoryException : StreamException()
    class FileInfoUnavailableException : StreamException()
}
