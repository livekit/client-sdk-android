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

import io.livekit.android.room.datastream.incoming.ByteStreamReceiver
import io.livekit.android.room.datastream.incoming.IncomingDataStreamManagerImpl
import io.livekit.android.test.BaseTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import livekit.LivekitModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        val streamInfo = ByteStreamInfo(
            id = "id",
            topic = "topic",
            timestampMs = 3,
            totalSize = null,
            attributes = mapOf(),
            mimeType = "mime",
            name = null,
            encryptionType = LivekitModels.Encryption.Type.NONE,
        )
        reader = ByteStreamReceiver(streamInfo, channel)
    }

    @Test
    fun buffersDataUntilSubscribed() = runTest {
        assertFalse(reader.isClosed)
        var count = 0
        runBlocking {
            reader.flow.collect {
                assertEquals(count, it[0].toInt())
                count++
            }
        }

        assertEquals(3, count)
        assertTrue(reader.isClosed)
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
        assertNull(reader.closeException)
        assertFalse(reader.isClosed)
        runBlocking {
            val data = reader.readAll()
            assertEquals(3, data.size)
            for (i in 0..2) {
                assertEquals(i, data[i][0].toInt())
            }
        }
        assertNull(reader.closeException)
        assertTrue(reader.isClosed)
    }

    @Test
    fun readAllSwallowsStreamException() = runTest {
        val errorChannel = IncomingDataStreamManagerImpl.createChannelForStreamReceiver()
        val errorReader = createReader(errorChannel)
        errorChannel.trySend(ByteArray(1) { 0 })
        errorChannel.trySend(ByteArray(1) { 1 })
        errorChannel.trySend(ByteArray(1) { 2 })
        val error = StreamException.AbnormalEndException("reason")
        errorChannel.close(error)

        runBlocking {
            assertEquals(error, errorReader.closeException)
            val data = errorReader.readAll()
            assertEquals(3, data.size)
            for (i in 0..2) {
                assertEquals(i, data[i][0].toInt())
            }
            assertEquals(error, errorReader.closeException)
        }
    }

    @Test
    fun isClosedFalseWhileOpen() = runTest {
        val openChannel = IncomingDataStreamManagerImpl.createChannelForStreamReceiver()
        val openReader = createReader(openChannel)

        assertFalse(openReader.isClosed)
    }

    @Test
    fun isClosedFalseWhenClosedWithBufferedData() = runTest {
        val bufferedChannel = IncomingDataStreamManagerImpl.createChannelForStreamReceiver()
        val bufferedReader = createReader(bufferedChannel)
        bufferedChannel.trySend(ByteArray(1) { 0 })
        bufferedChannel.trySend(ByteArray(1) { 1 })
        bufferedChannel.close()

        assertFalse(bufferedReader.isClosed)
    }

    @Test
    fun isClosedTrueAfterReadAll() = runTest {
        val drainedChannel = IncomingDataStreamManagerImpl.createChannelForStreamReceiver()
        val drainedReader = createReader(drainedChannel)
        drainedChannel.trySend(ByteArray(1) { 0 })
        drainedChannel.close()

        runBlocking {
            drainedReader.readAll()
        }

        assertTrue(drainedReader.isClosed)
    }

    @Test
    fun isClosedTrueAfterReadNextDrainsChannel() = runTest {
        val drainedChannel = IncomingDataStreamManagerImpl.createChannelForStreamReceiver()
        val drainedReader = createReader(drainedChannel)
        drainedChannel.trySend(ByteArray(1) { 0 })
        drainedChannel.close()

        runBlocking {
            drainedReader.readNext()
        }

        assertTrue(drainedReader.isClosed)
    }

    @Test
    fun isClosedTrueWhenClosedWithNoData() = runTest {
        val emptyChannel = IncomingDataStreamManagerImpl.createChannelForStreamReceiver()
        val emptyReader = createReader(emptyChannel)
        emptyChannel.close()

        assertTrue(emptyReader.isClosed)
    }

    @Test
    fun isClosedTrueAfterReadAllDespiteAbnormalClose() = runTest {
        val errorChannel = IncomingDataStreamManagerImpl.createChannelForStreamReceiver()
        val errorReader = createReader(errorChannel)
        errorChannel.trySend(ByteArray(1) { 0 })
        errorChannel.close(StreamException.AbnormalEndException("reason"))

        assertFalse(errorReader.isClosed)

        runBlocking {
            errorReader.readAll()
        }

        assertTrue(errorReader.isClosed)
        assertTrue(errorReader.closeException is StreamException.AbnormalEndException)
    }

    @Test
    fun closeExceptionNullWhileOpen() = runTest {
        val openChannel = IncomingDataStreamManagerImpl.createChannelForStreamReceiver()
        val openReader = createReader(openChannel)

        assertNull(openReader.closeException)
        assertFalse(openReader.isClosed)
    }

    @Test
    fun closeExceptionNullOnNormalClose() = runTest {
        val normalChannel = IncomingDataStreamManagerImpl.createChannelForStreamReceiver()
        val normalReader = createReader(normalChannel)
        normalChannel.trySend(ByteArray(1) { 0 })
        normalChannel.close()

        assertNull(normalReader.closeException)
    }

    @Test
    fun closeExceptionSetOnAbnormalClose() = runTest {
        val errorChannel = IncomingDataStreamManagerImpl.createChannelForStreamReceiver()
        val errorReader = createReader(errorChannel)
        val error = StreamException.LengthExceededException()
        errorChannel.close(error)

        assertEquals(error, errorReader.closeException)
    }

    @Test
    fun closeExceptionSetWhenChannelAlreadyClosed() = runTest {
        val errorChannel = IncomingDataStreamManagerImpl.createChannelForStreamReceiver()
        val error = StreamException.TerminatedException()
        errorChannel.close(error)
        val errorReader = createReader(errorChannel)

        assertEquals(error, errorReader.closeException)
    }

    @Test
    fun closeExceptionAvailableBeforeDrain() = runTest {
        val errorChannel = IncomingDataStreamManagerImpl.createChannelForStreamReceiver()
        val errorReader = createReader(errorChannel)
        errorChannel.trySend(ByteArray(1) { 0 })
        errorChannel.trySend(ByteArray(1) { 1 })
        val error = StreamException.IncompleteException()
        errorChannel.close(error)

        assertEquals(error, errorReader.closeException)
        assertFalse(errorReader.isClosed)

        runBlocking {
            assertEquals(2, errorReader.readAll().size)
        }
        assertEquals(error, errorReader.closeException)
        assertTrue(errorReader.isClosed)
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

    private fun createReader(channel: Channel<ByteArray>): ByteStreamReceiver {
        return ByteStreamReceiver(
            ByteStreamInfo(
                id = "id",
                topic = "topic",
                timestampMs = 3,
                totalSize = null,
                attributes = mapOf(),
                mimeType = "mime",
                name = null,
                encryptionType = LivekitModels.Encryption.Type.NONE,
            ),
            channel,
        )
    }
}
