package io.livekit.android.test.mock.room.datastream.outgoing

import io.livekit.android.room.datastream.outgoing.DataChunker
import io.livekit.android.room.datastream.outgoing.StreamDestination

class MockStreamDestination<T>(val chunkSize: Int) : StreamDestination<T> {
    override var isOpen: Boolean = true

    val writtenChunks = mutableListOf<ByteArray>()
    override suspend fun write(data: T, chunker: DataChunker<T>) {
        val chunks = chunker.invoke(data, chunkSize)

        for (chunk in chunks) {
            writtenChunks.add(chunk)
        }
    }

    override suspend fun close(reason: String?) {
        isOpen = false
    }
}
