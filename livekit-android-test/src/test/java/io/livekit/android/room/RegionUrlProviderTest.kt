/*
 * Copyright 2024-2026 LiveKit, Inc.
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /**
     * The attempted set scopes one failover cycle. Once it is cleared the whole region list is
     * eligible again, so a region that failed transiently is retried on the next cycle rather
     * than being skipped for the life of the provider.
     */
    @Test
    fun clearAttemptedRegionsMakesEveryRegionEligibleAgain() = runTest {
        server = MockWebServer()
        server.enqueue(MockResponse().setBody(regionResponse))
        val regionUrlProvider = RegionUrlProvider(server.url("").toUri(), "token", OkHttpClient.Builder().build(), Json { ignoreUnknownKeys = true })

        assertEquals("https://regiona.livekit.cloud", regionUrlProvider.getNextBestRegionUrl())
        assertEquals("https://regionb.livekit.cloud", regionUrlProvider.getNextBestRegionUrl())

        regionUrlProvider.clearAttemptedRegions()

        // Back to the best region, not the next unattempted one.
        assertEquals("https://regiona.livekit.cloud", regionUrlProvider.getNextBestRegionUrl())

        // Still served from the cached settings.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun trustedRegionUrlAcceptsEncryptedCloudUrls() {
        assertTrue(isTrustedRegionUrl("wss://eu.livekit.cloud"))
        assertTrue(isTrustedRegionUrl("https://eu.livekit.cloud"))
        assertTrue(isTrustedRegionUrl("https://eu.livekit.cloud:443/rtc"))
        assertTrue(isTrustedRegionUrl("wss://staging.livekit.run"))
        assertTrue(isTrustedRegionUrl("https://EU.LIVEKIT.CLOUD"))
    }

    @Test
    fun trustedRegionUrlRejectsCleartextSchemes() {
        // The transport carrying the token must stay encrypted, even to a real cloud host.
        assertFalse(isTrustedRegionUrl("ws://eu.livekit.cloud"))
        assertFalse(isTrustedRegionUrl("http://eu.livekit.cloud"))
    }

    @Test
    fun trustedRegionUrlRejectsForeignHosts() {
        assertFalse(isTrustedRegionUrl("https://other.example.com"))
        assertFalse(isTrustedRegionUrl("wss://other.example.com/rtc"))
        // Near-misses must not match on suffix alone.
        assertFalse(isTrustedRegionUrl("https://not-livekit.cloud"))
        assertFalse(isTrustedRegionUrl("https://livekit.cloud.example.com"))
    }

    @Test
    fun trustedRegionUrlRejectsMalformedAndHostlessUrls() {
        assertFalse(isTrustedRegionUrl(""))
        assertFalse(isTrustedRegionUrl("not a url"))
        assertFalse(isTrustedRegionUrl("eu.livekit.cloud"))
        assertFalse(isTrustedRegionUrl("file:///etc/passwd"))
    }

    /** A fetched region the SDK must not fail over to is dropped, not connected to with the token. */
    @Test
    fun rejectsUntrustedFetchedRegions() = runTest {
        server = MockWebServer()
        server.enqueue(MockResponse().setBody(untrustedRegionResponse))
        val regionUrlProvider = RegionUrlProvider(server.url("").toUri(), "token", OkHttpClient.Builder().build(), Json { ignoreUnknownKeys = true })

        val settings = regionUrlProvider.fetchRegionSettings()
        assertNotNull(settings)
        assertEquals(listOf("https://good.livekit.cloud"), settings!!.regions.map { it.url })

        assertEquals("https://good.livekit.cloud", regionUrlProvider.getNextBestRegionUrl())
        assertNull(regionUrlProvider.getNextBestRegionUrl())
    }

    /** Server-reported regions arrive over signaling and are the second ingestion path. */
    @Test
    fun rejectsUntrustedServerReportedRegions() = runTest {
        server = MockWebServer()
        val regionUrlProvider = RegionUrlProvider(server.url("").toUri(), "token", OkHttpClient.Builder().build(), Json { ignoreUnknownKeys = true })

        regionUrlProvider.setServerReportedRegions(
            RegionSettings(
                listOf(
                    RegionInfo("foreign", "https://other.example.com", 1L),
                    RegionInfo("cleartext", "ws://cleartext.livekit.cloud", 2L),
                    RegionInfo("good", "https://good.livekit.cloud", 3L),
                ),
            ),
        )

        assertEquals("https://good.livekit.cloud", regionUrlProvider.getNextBestRegionUrl())
        assertNull(regionUrlProvider.getNextBestRegionUrl())
        // No fetch was needed: the server-reported list was used directly.
        assertEquals(0, server.requestCount)
    }
}

private val untrustedRegionResponse = """{
    "regions": [
        {
            "region": "foreign",
            "url": "https://other.example.com",
            "distance": "1"
        },
        {
            "region": "cleartext",
            "url": "ws://cleartext.livekit.cloud",
            "distance": "2"
        },
        {
            "region": "good",
            "url": "https://good.livekit.cloud",
            "distance": "3"
        }
    ]
}"""

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
