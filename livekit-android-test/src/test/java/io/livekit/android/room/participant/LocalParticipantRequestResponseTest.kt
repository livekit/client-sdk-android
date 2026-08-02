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

package io.livekit.android.room.participant

import io.livekit.android.room.signal.SignalRequestException
import io.livekit.android.room.signal.SignalResponseReason
import io.livekit.android.test.MockE2ETest
import io.livekit.android.test.mock.TestData
import io.livekit.android.test.util.toPBByteString
import io.livekit.android.util.TimeoutException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import livekit.LivekitModels
import livekit.LivekitRtc
import livekit.LivekitRtc.ParticipantUpdate
import livekit.LivekitRtc.RequestResponse
import livekit.LivekitRtc.SignalResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class LocalParticipantRequestResponseTest : MockE2ETest() {

    @Test
    fun setMetadataSendsRequestId() = runTest {
        connect()
        registerMetadataConfirmationHandler()
        wsFactory.ws.clearRequests()

        val result = room.localParticipant.setMetadata("new_metadata")

        assertTrue(result.isSuccess)
        val sentRequest = parseSentUpdateMetadataRequest()
        assertTrue(sentRequest.updateMetadata.requestId > 0)
        assertEquals("new_metadata", sentRequest.updateMetadata.metadata)
    }

    @Test
    fun setMetadataSucceedsOnParticipantUpdate() = runTest {
        connect()
        registerMetadataConfirmationHandler()

        val newMetadata = "confirmed_metadata"
        val result = room.localParticipant.setMetadata(newMetadata)

        assertTrue(result.isSuccess)
        assertEquals(newMetadata, room.localParticipant.metadata)
    }

    @Test
    fun setNameSucceedsOnParticipantUpdate() = runTest {
        connect()
        registerMetadataConfirmationHandler()

        val newName = "confirmed_name"
        val result = room.localParticipant.setName(newName)

        assertTrue(result.isSuccess)
        assertEquals(newName, room.localParticipant.name)
    }

    @Test
    fun setAttributesSucceedsOnParticipantUpdate() = runTest {
        connect()
        registerMetadataConfirmationHandler()

        val newAttributes = mapOf("attribute" to "changedValue")
        val result = room.localParticipant.setAttributes(newAttributes)

        assertTrue(result.isSuccess)
        assertEquals("changedValue", room.localParticipant.attributes["attribute"])
    }

    @Test
    fun setMetadataFailsOnRequestResponseError() = runTest {
        connect()
        wsFactory.registerSignalRequestHandler { request ->
            if (request.hasUpdateMetadata()) {
                wsFactory.receiveMessage(
                    requestResponse(
                        requestId = request.updateMetadata.requestId,
                        reason = RequestResponse.Reason.NOT_ALLOWED,
                        message = "not allowed",
                    ),
                )
                return@registerSignalRequestHandler true
            }
            return@registerSignalRequestHandler false
        }

        val result = room.localParticipant.setMetadata("new_metadata")

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is SignalRequestException)
        error as SignalRequestException
        assertEquals(SignalResponseReason.NOT_ALLOWED, error.reason)
        assertEquals("not allowed", error.message)
    }

    @Test
    fun setMetadataTimesOutWithoutConfirmation() = runTest {
        connect()
        wsFactory.ws.clearRequests()

        val deferred = async {
            room.localParticipant.setMetadata("new_metadata")
        }
        coroutineRule.dispatcher.scheduler.advanceTimeBy(5_001)

        val result = deferred.await()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is TimeoutException)
    }

    private fun registerMetadataConfirmationHandler() {
        wsFactory.registerSignalRequestHandler { request ->
            if (request.hasUpdateMetadata()) {
                val update = request.updateMetadata
                val newInfo = with(TestData.LOCAL_PARTICIPANT.toBuilder()) {
                    if (update.metadata.isNotEmpty()) {
                        metadata = update.metadata
                    }
                    if (update.name.isNotEmpty()) {
                        name = update.name
                    }
                    if (update.attributesCount > 0) {
                        putAllAttributes(update.attributesMap)
                    }
                    build()
                }
                wsFactory.receiveMessage(participantUpdate(newInfo))
                return@registerSignalRequestHandler true
            }
            return@registerSignalRequestHandler false
        }
    }

    private fun parseSentUpdateMetadataRequest(): LivekitRtc.SignalRequest {
        val requestString = wsFactory.ws.sentRequests.first().toPBByteString()
        return LivekitRtc.SignalRequest.newBuilder()
            .mergeFrom(requestString)
            .build()
    }

    private fun participantUpdate(participant: LivekitModels.ParticipantInfo): SignalResponse {
        return SignalResponse.newBuilder()
            .setUpdate(
                ParticipantUpdate.newBuilder()
                    .addParticipants(participant)
                    .build(),
            )
            .build()
    }

    private fun requestResponse(
        requestId: Int,
        reason: RequestResponse.Reason,
        message: String = "",
    ): SignalResponse {
        return SignalResponse.newBuilder()
            .setRequestResponse(
                RequestResponse.newBuilder()
                    .setRequestId(requestId)
                    .setReason(reason)
                    .setMessage(message)
                    .build(),
            )
            .build()
    }
}
