/*
 * Copyright 2024 LiveKit, Inc.
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

import io.livekit.android.test.BaseTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RegionUrlProviderTest : BaseTest() {

    lateinit var server: MockWebServer

    @Before
    fun setup() {
        setRegionUrlProviderTesting(true)
    }

    @After
    fun tearDown() {
        setRegionUrlProviderTesting(false)
    }

    @Test
    fun fetchRegionSettings() = runTest {
        server = MockWebServer()
        server.enqueue(MockResponse().setBody(regionResponse))
        val regionUrlProvider = RegionUrlProvider(server.url("").toUri(), "token", OkHttpClient.Builder().build(), Json { ignoreUnknownKeys = true })

        val settings = regionUrlProvider.fetchRegionSettings()
        assertNotNull(settings)

        val regions = settings!!.regions
        assertNotNull(regions)
        assertEquals(3, regions.size)
        val regionA = regions[0]
        assertEquals("a", regionA.region)
        assertEquals("https://regiona.livekit.cloud", regionA.url)
        assertEquals(100L, regionA.distance)
    }

    @Test
    fun getNextRegionUrl() = runTest {
        server = MockWebServer()
        server.enqueue(MockResponse().setBody(regionResponse))
        val regionUrlProvider = RegionUrlProvider(server.url("").toUri(), "token", OkHttpClient.Builder().build(), Json { ignoreUnknownKeys = true })

        assertEquals("https://regiona.livekit.cloud", regionUrlProvider.getNextBestRegionUrl())
        assertEquals("https://regionb.livekit.cloud", regionUrlProvider.getNextBestRegionUrl())
        assertEquals("https://regionc.livekit.cloud", regionUrlProvider.getNextBestRegionUrl())
        assertNull(regionUrlProvider.getNextBestRegionUrl())

        // Check that only one request was needed.
        assertEquals(1, server.requestCount)
    }
}

private val regionResponse = """{
    "regions": [
        {
            "region": "a",
            "url": "https://regiona.livekit.cloud",
            "distance": "100"
        },
        {
            "region": "b",
            "url": "https://regionb.livekit.cloud",
            "distance": "1000"
        },
        {
            "region": "c",
            "url": "https://regionc.livekit.cloud",
            "distance": "10000"
        }
    ]
}"""
