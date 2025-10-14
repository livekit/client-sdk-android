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

package io.livekit.android.proto

import io.livekit.android.room.RegionSettings
import io.livekit.android.room.participant.ParticipantPermission
import io.livekit.android.rpc.RpcError
import io.livekit.android.token.RoomAgentDispatch
import io.livekit.android.token.RoomConfiguration
import io.livekit.android.token.TokenSourceRequest
import io.livekit.android.token.TokenSourceResponse
import livekit.LivekitAgentDispatch
import livekit.LivekitModels
import livekit.LivekitRoom
import livekit.LivekitRtc
import livekit.LivekitTokenSource
import livekit.org.webrtc.SessionDescription
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * A test that ensures all the proto enum cases match their sdk counterparts.
 */
@RunWith(Parameterized::class)
class ProtoConverterTest(
    val protoClass: Class<*>,
    val sdkClass: Class<*>,
    /** fields to ignore */
    val whitelist: List<String>,
    /** field mapping proto field to sdk field */
    val mapping: Map<String, String>,
    @Suppress("unused") val testName: String,
) {

    data class ProtoConverterTestCase(
        val protoClass: Class<*>,
        val sdkClass: Class<*>,
        /** fields to ignore */
        val whitelist: List<String> = emptyList(),
        /** field mapping proto field to sdk field */
        val mapping: Map<String, String> = emptyMap(),
    ) {
        fun toTestData(): Array<Any> {
            return arrayOf(protoClass, sdkClass, whitelist, mapping, protoClass.simpleName)
        }
    }

    companion object {
        val testCases = listOf(
            ProtoConverterTestCase(
                LivekitModels.ParticipantPermission::class.java,
                ParticipantPermission::class.java,
                whitelist = listOf("agent"),
            ),
            ProtoConverterTestCase(
                LivekitRtc.RegionSettings::class.java,
                RegionSettings::class.java,
            ),
            ProtoConverterTestCase(
                LivekitModels.RpcError::class.java,
                RpcError::class.java,
            ),
            ProtoConverterTestCase(
                LivekitRtc.SessionDescription::class.java,
                SessionDescription::class.java,
                mapping = mapOf("sdp" to "description"),
                whitelist = listOf("id"),
            ),
            ProtoConverterTestCase(
                LivekitTokenSource.TokenSourceRequest::class.java,
                TokenSourceRequest::class.java,
            ),
            ProtoConverterTestCase(
                LivekitTokenSource.TokenSourceResponse::class.java,
                TokenSourceResponse::class.java,
            ),
            ProtoConverterTestCase(
                LivekitRoom.RoomConfiguration::class.java,
                RoomConfiguration::class.java,
                whitelist = listOf("egress"),
            ),
            ProtoConverterTestCase(
                LivekitAgentDispatch.RoomAgentDispatch::class.java,
                RoomAgentDispatch::class.java,
            ),
        )

        @JvmStatic
        @Parameterized.Parameters(name = "Input: {4}")
        fun params(): List<Array<Any>> {
            return testCases.map { it.toTestData() }
        }
    }

    @Test
    fun participantPermission() {
        val protoFields = protoClass.declaredFields
            .asSequence()
            .map { it.name }
            .filter { it.isNotBlank() }
            .filter { it[0].isLowerCase() }
            .map { it.slice(0 until it.indexOf('_')) } // Internally fields may have underscores attached to them.
            .filter { it.isNotBlank() }
            .filterNot { whitelist.contains(it) }
            .filterNot { it == "bitField0" } // Internal field not related to the declared protobuf structure
            .map { mapping[it] ?: it }
            .toSet()
        val fields = sdkClass.declaredFields
            .map { it.name }
            .toSet()

        println("Local fields")
        fields.forEach { println(it) }
        println()
        println("Proto fields")
        protoFields.forEach { println(it) }

        for (protoField in protoFields) {
            assertTrue("$protoField not found!", fields.contains(protoField))
        }
    }
}
