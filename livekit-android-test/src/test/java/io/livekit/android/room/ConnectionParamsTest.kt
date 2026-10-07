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

package io.livekit.android.room

import io.livekit.android.ConnectOptions
import io.livekit.android.stats.getClientInfo
import io.livekit.android.test.MockE2ETest
import io.livekit.android.test.mock.TestData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import livekit.LivekitModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What this SDK tells the server, and through it every peer, about the data stream features it
 * supports.
 *
 * Getting this wrong is invisible locally and only shows up as peers never using v2 framings, so
 * it is asserted on the actual connect URL rather than on the values feeding it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionParamsTest : MockE2ETest() {

    @Test
    fun advertisesDataStreamV2ClientProtocol() = runTest {
        connect()

        val url = wsFactory.request.url.toString()

        assertTrue(
            "connect URL should advertise client_protocol=2, was: $url",
            url.contains("client_protocol=${ClientProtocolVersion.DATA_STREAM_V2.value}"),
        )
    }

    @Test
    fun advertisesCompressionCapability() = runTest {
        connect()

        val url = wsFactory.request.url.toString()
        val capabilities = wsFactory.request.url.queryParameter("capabilities")

        assertTrue(
            "connect URL should carry a capabilities param, was: $url",
            capabilities != null,
        )
        // The server parses these as protobuf enum names, so the exact spelling is wire contract.
        assertTrue(
            "capabilities should advertise deflate-raw support, was: $capabilities",
            capabilities!!.split(",").contains("CAP_COMPRESSION_DEFLATE_RAW"),
        )
    }

    /**
     * Compression is done by the Rust core rather than a platform codec, so there is no device or
     * API level on which we support v2 but cannot decompress.
     */
    @Test
    fun clientInfoCarriesTheSameCapabilities() {
        val clientInfo = getClientInfo(ClientProtocolVersion.DATA_STREAM_V2)

        assertEquals(ClientProtocolVersion.DATA_STREAM_V2.value, clientInfo.clientProtocol)
        assertEquals(
            listOf(LivekitModels.ClientInfo.Capability.CAP_COMPRESSION_DEFLATE_RAW),
            clientInfo.capabilitiesList,
        )
    }

    @Test
    fun clientProtocolIsOverridableByConnectOptions() = runTest {
        val job = coroutineRule.scope.launch {
            room.connect(
                url = TestData.EXAMPLE_URL,
                token = "token",
                options = ConnectOptions(clientProtocol = ClientProtocolVersion.DATA_STREAM_RPC),
            )
        }
        prepareSignal()
        job.join()

        val url = wsFactory.request.url.toString()

        assertTrue(
            "explicit clientProtocol should be honored, was: $url",
            url.contains("client_protocol=${ClientProtocolVersion.DATA_STREAM_RPC.value}"),
        )
    }
}
