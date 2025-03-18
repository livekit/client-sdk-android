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

import io.livekit.android.room.datastream.incoming.ByteStreamReceiver
import io.livekit.android.room.datastream.incoming.IncomingDataStreamManagerImpl
import io.livekit.android.test.BaseTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamReaderTest : BaseTest() {

    lateinit var channel: Channel<ByteArray>
    lateinit var reader: ByteStreamReceiver

    @Before
    fun setup() {
        channel = IncomingDataStreamManagerImpl.createChannelForStreamReceiver()
        channel.trySend(ByteArray(1) { 0 })
        channel.trySend(ByteArray(1) { 1 })
        channel.trySend(ByteArray(1) { 2 })
        channel.close()
        val streamInfo = ByteStreamInfo(id = "id", topic = "topic", timestampMs = 3, totalSize = null, attributes = mapOf(), mimeType = "mime", name = null)
        reader = ByteStreamReceiver(streamInfo, channel)
    }

    @Test
    fun buffersDataUntilSubscribed() = runTest {
        var count = 0
        runBlocking {
            reader.flow.collect {
                assertEquals(count, it[0].toInt())
                count++
            }
        }

        assertEquals(3, count)
    }

    @Test
    fun readEach() = runTest {
        runBlocking {
            for (i in 0..2) {
                val next = reader.readNext()
                assertEquals(i, next[0].toInt())
            }
        }
    }

    @Test
    fun readAll() = runTest {
        runBlocking {
            val data = reader.readAll()
            assertEquals(3, data.size)
            for (i in 0..2) {
                assertEquals(i, data[i][0].toInt())
            }
        }
    }

    @Test
    fun overreadThrows() = runTest {
        var threwOnce = false
        runBlocking {
            try {
                for (i in 0..3) {
                    val next = reader.readNext()
                    assertEquals(i, next[0].toInt())
                }
            } catch (e: NoSuchElementException) {
                threwOnce = true
            }
        }

        assertTrue(threwOnce)
    }
}
