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

package io.livekit.android.room.signal

import livekit.LivekitRtc

/**
 * Error returned when a signal request is rejected by the server
 */
class SignalRequestException(
    message: String? = null,
    val reason: SignalResponseReason,
    cause: Throwable? = null,
) : Exception(message, cause) {

    companion object {
        fun fromResponse(response: LivekitRtc.RequestResponse): SignalRequestException {
            return SignalRequestException(
                message = response.message.takeIf { it.isNotEmpty() },
                reason = SignalResponseReason.fromProto(response.reason),
            )
        }
    }
}

enum class SignalResponseReason {
    OK,
    NOT_FOUND,
    NOT_ALLOWED,
    LIMIT_EXCEEDED,
    QUEUED,
    UNSUPPORTED_TYPE,
    UNCLASSIFIED_ERROR,
    INVALID_HANDLE,
    INVALID_NAME,
    DUPLICATE_HANDLE,
    DUPLICATE_NAME,
    UNRECOGNIZED;

    companion object {
        internal fun fromProto(proto: LivekitRtc.RequestResponse.Reason): SignalResponseReason {
            return when (proto) {
                LivekitRtc.RequestResponse.Reason.OK -> OK
                LivekitRtc.RequestResponse.Reason.NOT_FOUND -> NOT_FOUND
                LivekitRtc.RequestResponse.Reason.NOT_ALLOWED -> NOT_ALLOWED
                LivekitRtc.RequestResponse.Reason.LIMIT_EXCEEDED -> LIMIT_EXCEEDED
                LivekitRtc.RequestResponse.Reason.QUEUED -> QUEUED
                LivekitRtc.RequestResponse.Reason.UNSUPPORTED_TYPE -> UNSUPPORTED_TYPE
                LivekitRtc.RequestResponse.Reason.UNCLASSIFIED_ERROR -> UNCLASSIFIED_ERROR
                LivekitRtc.RequestResponse.Reason.INVALID_HANDLE -> INVALID_HANDLE
                LivekitRtc.RequestResponse.Reason.INVALID_NAME -> INVALID_NAME
                LivekitRtc.RequestResponse.Reason.DUPLICATE_HANDLE -> DUPLICATE_HANDLE
                LivekitRtc.RequestResponse.Reason.DUPLICATE_NAME -> DUPLICATE_NAME
                else -> UNRECOGNIZED
            }
        }
    }
}
