package io.livekit.android.room.datastream.outgoing

import io.livekit.android.room.datastream.TextStreamInfo
import io.livekit.android.test.BaseTest
import io.livekit.android.test.mock.room.datastream.outgoing.MockStreamDestination
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TextStreamSenderTest : BaseTest() {

    companion object {
        val CHUNK_SIZE = 20
    }

    @Test
    fun sendsSingle() = runTest {

        val destination = MockStreamDestination<String>(CHUNK_SIZE)
        val sender = TextStreamSender(
            info = createInfo(),
            destination = destination,
        )

        val text = "abcdefghi"
        val job = launch {
            sender.write(text)
            sender.close()
        }

        job.join()

        assertFalse(destination.isOpen)
        assertEquals(1, destination.writtenChunks.size)
        assertEquals(text, destination.writtenChunks[0].decodeToString())
    }

    @Test
    fun sendsChunks() = runTest {

        val destination = MockStreamDestination<String>(CHUNK_SIZE)
        val sender = TextStreamSender(
            info = createInfo(),
            destination = destination,
        )

        val text = with(StringBuilder()) {
            for (i in 1..CHUNK_SIZE) {
                append("abcdefghi")
            }
            toString()
        }

        val job = launch {
            sender.write(text)
            sender.close()
        }

        job.join()

        assertFalse(destination.isOpen)
        assertNotEquals(1, destination.writtenChunks.size)

        val writtenString = with(StringBuilder()) {
            for (chunk in destination.writtenChunks) {
                append(chunk.decodeToString())
            }
            toString()
        }

        assertEquals(text, writtenString)
    }

    fun createInfo(): TextStreamInfo = TextStreamInfo(
        id = "stream_id",
        topic = "topic",
        timestampMs = 0,
        totalSize = null,
        attributes = mapOf(),
        operationType = TextStreamInfo.OperationType.CREATE,
        version = 0,
        replyToStreamId = null,
        attachedStreamIds = listOf(),
        generated = false,
    )

}
