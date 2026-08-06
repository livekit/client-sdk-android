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

package io.livekit.android.room.datastream

import io.livekit.android.memory.CloseableManager
import io.livekit.android.room.ClientCapability
import io.livekit.android.room.ClientProtocolVersion
import io.livekit.android.room.RTCEngine
import io.livekit.android.room.datastream.incoming.ByteStreamHandler
import io.livekit.android.room.datastream.incoming.ByteStreamReceiver
import io.livekit.android.room.datastream.incoming.TextStreamHandler
import io.livekit.android.room.datastream.incoming.TextStreamReceiver
import io.livekit.android.room.datastream.outgoing.ByteStreamSender
import io.livekit.android.room.datastream.outgoing.DataChunker
import io.livekit.android.room.datastream.outgoing.StreamDestination
import io.livekit.android.room.datastream.outgoing.TextStreamSender
import io.livekit.android.room.participant.Participant
import io.livekit.android.util.LKLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import livekit.LivekitModels
import java.io.Closeable
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton
import io.livekit.uniffi.ByteStreamInfo as FfiByteStreamInfo
import io.livekit.uniffi.ByteStreamReader as FfiByteStreamReader
import io.livekit.uniffi.ByteStreamWriter as FfiByteStreamWriter
import io.livekit.uniffi.ClientCapability as FfiClientCapability
import io.livekit.uniffi.DataStreamException as FfiDataStreamException
import io.livekit.uniffi.IncomingDataStreamManager as FfiIncomingDataStreamManager
import io.livekit.uniffi.IncomingDataStreamManagerDelegate as FfiIncomingDelegate
import io.livekit.uniffi.OperationType as FfiOperationType
import io.livekit.uniffi.OutgoingDataStreamManager as FfiOutgoingDataStreamManager
import io.livekit.uniffi.OutgoingDataStreamManagerDelegate as FfiOutgoingDelegate
import io.livekit.uniffi.RemoteParticipantRegistryDelegate as FfiRegistryDelegate
import io.livekit.uniffi.StreamByteOptions as FfiStreamByteOptions
import io.livekit.uniffi.StreamTextOptions as FfiStreamTextOptions
import io.livekit.uniffi.TextStreamInfo as FfiTextStreamInfo
import io.livekit.uniffi.TextStreamReader as FfiTextStreamReader
import io.livekit.uniffi.TextStreamWriter as FfiTextStreamWriter

/**
 * Owns the livekit-uniffi data stream managers and the topic to handler registry, and routes
 * packets between them and [RTCEngine].
 *
 * This is the single place the FFI is touched. Everything above it -- the
 * [io.livekit.android.room.datastream.incoming.IncomingDataStreamManager] and
 * [io.livekit.android.room.datastream.outgoing.OutgoingDataStreamManager] implementations, the
 * public sender and receiver types -- deals only in this SDK's own types.
 *
 * Room-scoped rather than session-scoped: stream handlers are registered before connecting (and by
 * internal RPC wiring on every connect), so they and the managers holding them have to survive
 * reconnects. Neither FFI manager holds a channel handle -- inbound packets are pushed in via
 * [handleIncoming] and outbound ones are pulled out through a delegate -- so there is nothing
 * transport-shaped to reopen when the connection changes.
 *
 * @suppress
 */
@Singleton
class DataStreams
@Inject
internal constructor(
    private val engine: RTCEngine,
    closeableManager: CloseableManager,
) : Closeable {

    /**
     * Room state the send path needs, assigned by [io.livekit.android.room.Room] after
     * construction.
     *
     * Injecting Room here would be a Dagger cycle, so these follow the same
     * assign-a-lambda-afterwards pattern Room already uses for the RPC managers'
     * `getRemoteClientProtocol`.
     */
    internal var remoteIdentities: () -> List<Participant.Identity> = { emptyList() }
    internal var remoteClientProtocol: (Participant.Identity) -> Int = { ClientProtocolVersion.DEFAULT.value }
    internal var remoteCapabilities: (Participant.Identity) -> List<ClientCapability> = { emptyList() }

    /**
     * Cap on the size of a reassembled incoming payload, from
     * [io.livekit.android.RoomOptions.dataStreamOptions]. Read lazily -- see [incomingManager].
     */
    internal var maxPayloadSize: () -> Long? = { null }

    /**
     * The dispatcher for everything on the FFI boundary: calls into the core, and the coroutines
     * that react to its callbacks.
     *
     * Deliberately a real dispatcher, and deliberately not the injected one. Two reasons, both of
     * which have bitten:
     *
     *  - The core resumes a suspended call from a thread on its own runtime, so the continuation
     *    has to land on a dispatcher actually backed by threads. On a virtual-time test dispatcher
     *    it is queued for a scheduler nobody is advancing and the call never completes.
     *  - The core invokes our delegates synchronously on its runtime threads. An unconfined
     *    dispatcher resumes waiting coroutines *inline on the calling thread*, so the work those
     *    coroutines do -- awaiting a publisher connection, waiting out data channel backpressure --
     *    would run on, and block, a core runtime thread. Enough of those and the core's runtime
     *    deadlocks and every stream stops.
     *
     * Confining all of it here keeps both hazards off callers, including SDK users whose own tests
     * may run the SDK on a test dispatcher.
     */
    private val ffiDispatcher: CoroutineDispatcher = Dispatchers.IO

    private val coroutineScope = CoroutineScope(SupervisorJob() + ffiDispatcher)

    private val textStreamHandlers = Collections.synchronizedMap(mutableMapOf<String, TextStreamHandler>())
    private val byteStreamHandlers = Collections.synchronizedMap(mutableMapOf<String, ByteStreamHandler>())

    /** Topics we have already warned about, so an unhandled topic logs once rather than per stream. */
    private val warnedTopics = Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * Outbound packets waiting to go on the wire.
     *
     * The FFI delegate is a plain synchronous callback on a Rust runtime thread: it can neither
     * block nor suspend, but sending has to await publisher connection and data channel
     * backpressure. Handing off through an unbounded channel drained by a single coroutine keeps
     * packets in the order the core emitted them while restoring the backpressure the previous
     * implementation had.
     */
    private val outboundPackets = Channel<ByteArray>(Channel.UNLIMITED)

    private val outgoing: FfiOutgoingDataStreamManager =
        FfiOutgoingDataStreamManager(OutgoingDelegate(), RegistryDelegate())

    private val incomingLock = Any()
    private var incoming: FfiIncomingDataStreamManager? = null

    init {
        closeableManager.registerClosable(this)
        coroutineScope.launch {
            for (packet in outboundPackets) {
                sendPacket(packet)
            }
        }
    }

    /**
     * The incoming manager, built on first use.
     *
     * Deliberately not built in `init`: its payload cap comes from the room options, which are not
     * final until `connect` -- after this class is constructed. The first inbound packet can only
     * arrive after connecting, so reading the cap here picks up a value passed to `connect`.
     */
    private fun incomingManager(): FfiIncomingDataStreamManager {
        synchronized(incomingLock) {
            incoming?.let { return it }
            val manager = FfiIncomingDataStreamManager(
                delegate = IncomingDelegate(),
                maxPayloadByteLength = maxPayloadSize()?.toULong(),
            )
            incoming = manager
            return manager
        }
    }

    // region Handler registration

    fun registerTextStreamHandler(topic: String, handler: TextStreamHandler) {
        synchronized(textStreamHandlers) {
            if (textStreamHandlers.containsKey(topic)) {
                throw IllegalArgumentException("A text stream handler for topic $topic has already been set.")
            }
            textStreamHandlers[topic] = handler
        }
    }

    fun unregisterTextStreamHandler(topic: String) {
        synchronized(textStreamHandlers) {
            textStreamHandlers.remove(topic)
        }
    }

    fun registerByteStreamHandler(topic: String, handler: ByteStreamHandler) {
        synchronized(byteStreamHandlers) {
            if (byteStreamHandlers.containsKey(topic)) {
                throw IllegalArgumentException("A byte stream handler for topic $topic has already been set.")
            }
            byteStreamHandlers[topic] = handler
        }
    }

    fun unregisterByteStreamHandler(topic: String) {
        synchronized(byteStreamHandlers) {
            byteStreamHandlers.remove(topic)
        }
    }

    // endregion

    // region Incoming

    /**
     * Feeds a received data stream packet to the core, which re-decodes it itself.
     *
     * Cheap and non-blocking: the packet is queued and processed on the core's own loop, so this is
     * safe to call from a data channel callback. Packets that are not data stream packets, or do
     * not decode, are ignored by the core.
     */
    fun handleIncoming(packet: LivekitModels.DataPacket) {
        incomingManager().handlePacketReceived(packet.toByteArray())
    }

    /**
     * Fails every open incoming stream so blocked readers raise instead of hanging.
     *
     * Handler registrations survive, so streams arriving after a reconnect are still delivered.
     * A no-op if no packet has ever been received, since nothing can be open.
     */
    fun abortAllStreams() {
        synchronized(incomingLock) { incoming }?.abortAllStreams()
    }

    /**
     * Fails open incoming streams sent by [identity], for when that participant disconnects
     * mid-send. Without this their readers would wait forever for chunks that will never arrive.
     */
    fun abortStreamsFrom(identity: Participant.Identity) {
        synchronized(incomingLock) { incoming }?.abortStreamsFrom(identity.value)
    }

    // endregion

    // region Outgoing

    suspend fun streamText(options: StreamTextOptions): TextStreamSender {
        val writer = onFfi { outgoing.streamText(options.toFfi()) }
        return TextStreamSender(
            info = writer.info().toSdk(currentEncryptionType()),
            destination = TextWriterDestination(writer),
        )
    }

    suspend fun streamBytes(options: StreamBytesOptions): ByteStreamSender {
        val writer = onFfi { outgoing.streamBytes(options.toFfi()) }
        return ByteStreamSender(
            info = writer.info().toSdk(currentEncryptionType()),
            destination = ByteWriterDestination(writer),
        )
    }

    suspend fun sendText(text: String, options: StreamTextOptions): TextStreamInfo {
        return onFfi { outgoing.sendText(text, options.toFfi()) }
            .toSdk(currentEncryptionType())
    }

    suspend fun sendBytes(data: ByteArray, options: StreamBytesOptions): ByteStreamInfo {
        return onFfi { outgoing.sendBytes(data, options.toFfi()) }
            .toSdk(currentEncryptionType())
    }

    /**
     * Sends [path] as a byte stream, read from disk by the core rather than buffered in memory.
     *
     * [options] is expected to already carry the name, MIME type and size resolved from the file:
     * the core reads the bytes but does not inspect the file's metadata.
     */
    suspend fun sendFile(path: String, options: StreamBytesOptions): ByteStreamInfo {
        return onFfi { outgoing.sendFile(path, options.toFfi()) }
            .toSdk(currentEncryptionType())
    }

    private suspend fun sendPacket(bytes: ByteArray) {
        val packet = try {
            LivekitModels.DataPacket.parseFrom(bytes)
        } catch (e: Exception) {
            LKLog.e(e) { "Unable to decode an outgoing data stream packet; dropping it." }
            return
        }

        engine.waitForBufferStatusLow(packet.kind)
        val result = engine.sendData(packet)
        if (result.isFailure) {
            // The core acknowledges the send as soon as it hands the packet over, so there is
            // nobody left to return this to; the originating send call has already returned.
            LKLog.w(result.exceptionOrNull()) { "Failed to send a data stream packet." }
        }
    }

    // endregion

    /**
     * The room's data channel encryption type.
     *
     * The core reports `NONE` on every stream: end to end encryption across the FFI is not
     * implemented, and payload encryption still happens in [RTCEngine] on the whole packet, either
     * side of the FFI. Stamping the room's real value keeps [StreamInfo.encryptionType] meaning
     * what it did before.
     */
    private fun currentEncryptionType(): LivekitModels.Encryption.Type {
        return if (engine.e2EEManager?.isDataChannelEncryptionEnabled() == true) {
            LivekitModels.Encryption.Type.GCM
        } else {
            LivekitModels.Encryption.Type.NONE
        }
    }

    override fun close() {
        coroutineScope.cancel()
        outboundPackets.close()
        // Releases the native handles, and with them the core's reference to our delegates. Those
        // delegates are held by a static handle map on the way in, so skipping this would keep this
        // object -- and through it the engine -- reachable for the life of the process.
        synchronized(incomingLock) {
            incoming?.destroy()
            incoming = null
        }
        outgoing.destroy()
    }

    // region FFI delegates

    /**
     * Receives encoded `DataPacket`s from the core and queues them for the reliable data channel.
     *
     * Unlike the Swift implementation this holds a strong reference to its owner: the JVM collects
     * reference cycles, so the weak back-reference Swift needs to break an ARC cycle would buy
     * nothing here. What does matter is [close] running, since the FFI's handle map holds this
     * delegate from a static root.
     */
    private inner class OutgoingDelegate : FfiOutgoingDelegate {
        override fun onPacketsAvailable(packets: List<ByteArray>) {
            for (packet in packets) {
                val result = outboundPackets.trySend(packet)
                if (result.isFailure) {
                    LKLog.w { "Dropping an outgoing data stream packet: the send queue is closed." }
                }
            }
        }
    }

    /**
     * Receives opened incoming streams from the core and routes them to a handler by topic.
     *
     * The core surfaces every stream regardless of topic; matching topics to handlers, and
     * discarding streams nobody is listening for, is this SDK's job.
     */
    private inner class IncomingDelegate : FfiIncomingDelegate {
        override fun onTextStreamOpened(reader: FfiTextStreamReader, identity: String) {
            val info = reader.info().toSdk(currentEncryptionType())
            val handler = textStreamHandlers[info.topic]
            if (handler == null) {
                warnMissingHandler("text", info.topic, info.id, identity)
                return
            }
            deliver {
                handler.invoke(
                    TextStreamReceiver(info, pumpText(reader)),
                    Participant.Identity(identity),
                )
            }
        }

        override fun onByteStreamOpened(reader: FfiByteStreamReader, identity: String) {
            val info = reader.info().toSdk(currentEncryptionType())
            val handler = byteStreamHandlers[info.topic]
            if (handler == null) {
                warnMissingHandler("byte", info.topic, info.id, identity)
                return
            }
            deliver {
                handler.invoke(
                    ByteStreamReceiver(info, pumpBytes(reader)),
                    Participant.Identity(identity),
                )
            }
        }
    }

    /**
     * Read access to the room's remote participants, used by the core to resolve a broadcast's
     * recipients and to decide per-recipient whether an inline or compressed framing is safe.
     *
     * Read live rather than cached: eligibility has to reflect who is in the room at send time.
     */
    private inner class RegistryDelegate : FfiRegistryDelegate {
        override fun remoteIdentities(): List<String> {
            return this@DataStreams.remoteIdentities().map { it.value }
        }

        override fun remoteClientProtocol(identity: String): Int {
            return this@DataStreams.remoteClientProtocol(Participant.Identity(identity))
        }

        override fun remoteCapabilities(identity: String): List<FfiClientCapability> {
            return this@DataStreams.remoteCapabilities(Participant.Identity(identity))
                .map { it.toFfi() }
        }
    }

    // endregion

    /**
     * Runs a stream handler off the FFI callback thread.
     *
     * Handlers are app code and are not required to return promptly, so running them inline would
     * let one of them stall the core's runtime thread and with it every other incoming stream.
     */
    private fun deliver(block: () -> Unit) {
        coroutineScope.launch {
            try {
                block()
            } catch (e: Exception) {
                LKLog.e(e) { "Unhandled exception when invoking stream handler!" }
            }
        }
    }

    private fun warnMissingHandler(kind: String, topic: String, id: String, identity: String) {
        if (warnedTopics.add(topic)) {
            LKLog.w {
                "Received $kind stream for topic \"$topic\", but no handler was found. Ignoring. " +
                    "(stream $id from $identity)"
            }
        }
    }

    /**
     * Drains an FFI reader into the channel the public receivers are built on.
     *
     * Keeps [io.livekit.android.room.datastream.incoming.BaseStreamReceiver] and its `flow` /
     * `readNext` / `readAll` surface exactly as it was: the channel closes normally when the
     * stream ends, or with a [StreamException] when it fails.
     */
    private fun pumpBytes(reader: FfiByteStreamReader): Channel<ByteArray> {
        return pump { reader.next() }
    }

    /**
     * As [pumpBytes], re-encoding each piece to UTF-8 because the public [TextStreamReceiver]
     * decodes from a byte channel.
     *
     * Lossless: the core splits text on character boundaries, so every piece is independently
     * valid UTF-8. Round-tripping keeps [TextStreamReceiver]'s public constructor untouched.
     */
    private fun pumpText(reader: FfiTextStreamReader): Channel<ByteArray> {
        return pump { reader.next()?.toByteArray(Charsets.UTF_8) }
    }

    private fun pump(next: suspend () -> ByteArray?): Channel<ByteArray> {
        val channel = Channel<ByteArray>(capacity = Channel.UNLIMITED)
        coroutineScope.launch {
            try {
                while (true) {
                    val chunk = withContext(ffiDispatcher) { next() } ?: break
                    channel.send(chunk)
                }
                channel.close()
            } catch (e: FfiDataStreamException) {
                channel.close(e.toStreamException())
            } catch (e: Exception) {
                channel.close(e)
            }
        }
        return channel
    }

    // region Writer destinations

    /**
     * Bridges a public sender onto an FFI writer.
     *
     * The [DataChunker] handed in by [io.livekit.android.room.datastream.outgoing.BaseStreamSender]
     * is deliberately ignored: chunking (including splitting text on character boundaries) now
     * happens in the core, which also needs the whole write to decide on framing.
     *
     * [isOpen] is a snapshot rather than a query. The interface exposes it as a non-suspending
     * property while the FFI's is a suspending call, so it is tracked locally: set false on close,
     * and on a failed write, since a write only fails once the stream is finished.
     */
    private abstract inner class WriterDestination<T> : StreamDestination<T> {
        @Volatile
        private var open = true

        override val isOpen: Boolean
            get() = open

        protected abstract suspend fun writeToFfi(data: T)
        protected abstract suspend fun closeFfi(reason: String?)

        override suspend fun write(data: T, chunker: DataChunker<T>): Result<Unit> {
            return try {
                withContext(ffiDispatcher) { writeToFfi(data) }
                Result.success(Unit)
            } catch (e: FfiDataStreamException) {
                open = false
                Result.failure(e.toStreamException())
            }
        }

        override suspend fun close(reason: String?) {
            if (!open) {
                return
            }
            open = false
            try {
                withContext(ffiDispatcher) { closeFfi(reason) }
            } catch (e: FfiDataStreamException) {
                throw e.toStreamException()
            }
        }
    }

    private inner class TextWriterDestination(
        private val writer: FfiTextStreamWriter,
    ) : WriterDestination<String>() {
        override suspend fun writeToFfi(data: String) = writer.write(data)
        override suspend fun closeFfi(reason: String?) {
            if (reason == null) writer.close() else writer.closeWithReason(reason)
        }
    }

    private inner class ByteWriterDestination(
        private val writer: FfiByteStreamWriter,
    ) : WriterDestination<ByteArray>() {
        override suspend fun writeToFfi(data: ByteArray) = writer.write(data)
        override suspend fun closeFfi(reason: String?) {
            if (reason == null) writer.close() else writer.closeWithReason(reason)
        }
    }

    // endregion

    /**
     * Runs a suspending call into the core on [ffiDispatcher], translating its errors.
     */
    private suspend fun <T> onFfi(body: suspend () -> T): T {
        try {
            return withContext(ffiDispatcher) { body() }
        } catch (e: FfiDataStreamException) {
            throw e.toStreamException()
        }
    }
}

// region FFI type conversions

internal fun FfiTextStreamInfo.toSdk(encryptionType: LivekitModels.Encryption.Type) = TextStreamInfo(
    id = id,
    topic = topic,
    timestampMs = timestampMs,
    totalSize = totalLength?.toLong(),
    attributes = attributes,
    operationType = operationType.toSdk(),
    version = version,
    replyToStreamId = replyToStreamId,
    attachedStreamIds = attachedStreamIds,
    generated = generated,
    encryptionType = encryptionType,
)

internal fun FfiByteStreamInfo.toSdk(encryptionType: LivekitModels.Encryption.Type) = ByteStreamInfo(
    id = id,
    topic = topic,
    timestampMs = timestampMs,
    totalSize = totalLength?.toLong(),
    attributes = attributes,
    mimeType = mimeType,
    name = name,
    encryptionType = encryptionType,
)

internal fun FfiOperationType.toSdk(): TextStreamInfo.OperationType = when (this) {
    FfiOperationType.CREATE -> TextStreamInfo.OperationType.CREATE
    FfiOperationType.UPDATE -> TextStreamInfo.OperationType.UPDATE
    FfiOperationType.DELETE -> TextStreamInfo.OperationType.DELETE
    FfiOperationType.REACTION -> TextStreamInfo.OperationType.REACTION
}

internal fun TextStreamInfo.OperationType.toFfi(): FfiOperationType = when (this) {
    TextStreamInfo.OperationType.CREATE -> FfiOperationType.CREATE
    TextStreamInfo.OperationType.UPDATE -> FfiOperationType.UPDATE
    TextStreamInfo.OperationType.DELETE -> FfiOperationType.DELETE
    TextStreamInfo.OperationType.REACTION -> FfiOperationType.REACTION
}

internal fun ClientCapability.toFfi(): FfiClientCapability = when (this) {
    ClientCapability.PACKET_TRAILER -> FfiClientCapability.PACKET_TRAILER
    ClientCapability.COMPRESSION_DEFLATE_RAW -> FfiClientCapability.COMPRESSION_DEFLATE_RAW
}

/**
 * `totalSize` is intentionally not carried over: the core opens an incremental text stream as
 * unknown-length, and its options have no field for a declared total.
 */
internal fun StreamTextOptions.toFfi() = FfiStreamTextOptions(
    topic = topic,
    attributes = attributes,
    destinationIdentities = destinationIdentities.map { it.value },
    id = streamId,
    operationType = operationType.toFfi(),
    version = version,
    replyToStreamId = replyToStreamId,
    attachedStreamIds = attachedStreamIds,
    generated = null,
    compress = compress,
    senderIdentity = null,
)

internal fun StreamBytesOptions.toFfi() = FfiStreamByteOptions(
    topic = topic,
    attributes = attributes,
    destinationIdentities = destinationIdentities.map { it.value },
    id = streamId,
    mimeType = mimeType,
    name = name,
    totalLength = totalSize?.toULong(),
    compress = compress,
    senderIdentity = null,
)

/**
 * Maps a core error onto this SDK's [StreamException] hierarchy, one to one.
 *
 * Every case the core can report is distinguishable here, either by its own exception type or by
 * [StreamException.TerminatedException.Reason]. The size failures are modelled as subclasses of
 * [StreamException.LengthExceededException] so that existing code catching that still catches them.
 */
internal fun FfiDataStreamException.toStreamException(): StreamException = when (this) {
    is FfiDataStreamException.AbnormalEnd -> StreamException.AbnormalEndException(reason)
    is FfiDataStreamException.Utf8 -> StreamException.DecodeFailedException(reason)
    is FfiDataStreamException.Decompression -> StreamException.DecodeFailedException("Decompression failed")
    is FfiDataStreamException.LengthExceeded -> StreamException.LengthExceededException(message)
    is FfiDataStreamException.HeaderTooLarge -> StreamException.HeaderTooLargeException(message)
    is FfiDataStreamException.PayloadTooLarge -> StreamException.PayloadTooLargeException(message)
    is FfiDataStreamException.Incomplete -> StreamException.IncompleteException()
    is FfiDataStreamException.EncryptionTypeMismatch -> StreamException.EncryptionTypeMismatch(message)
    is FfiDataStreamException.Internal -> StreamException.InternalException(message)

    // No dedicated type; told apart by their reason.
    is FfiDataStreamException.AlreadyClosed ->
        StreamException.TerminatedException(message, StreamException.TerminatedException.Reason.ALREADY_CLOSED)

    is FfiDataStreamException.InvalidHeader ->
        StreamException.TerminatedException(message, StreamException.TerminatedException.Reason.INVALID_HEADER)

    is FfiDataStreamException.MissedChunk ->
        StreamException.TerminatedException(message, StreamException.TerminatedException.Reason.MISSED_CHUNK)

    is FfiDataStreamException.SendFailed ->
        StreamException.TerminatedException(message, StreamException.TerminatedException.Reason.SEND_FAILED)

    is FfiDataStreamException.InvalidFileName ->
        StreamException.TerminatedException(message, StreamException.TerminatedException.Reason.INVALID_FILE_NAME)

    // A local file read or write failing is not the remote closing on us, so this is terminated
    // rather than an abnormal end.
    is FfiDataStreamException.Io ->
        StreamException.TerminatedException(reason, StreamException.TerminatedException.Reason.IO)
}

// endregion
