package io.livekit.android.room.track

import org.webrtc.DataChannel
import java.nio.ByteBuffer
import java.util.*

class LocalDataTrack(
    val options: DataTrackOptions
) : DataTrack(options.name) {
    var sid: String? = null
        internal set
    var cid: String = UUID.randomUUID().toString()

    fun sendString(message: String) {
        val byteBuffer = ByteBuffer.wrap(message.toByteArray())
        val buffer = DataChannel.Buffer(byteBuffer, false)
        dataChannel?.send(buffer)
    }

    fun sendBytes(byteBuffer: ByteBuffer) {
        val buffer = DataChannel.Buffer(byteBuffer, true)
        dataChannel?.send(buffer)
    }
}
