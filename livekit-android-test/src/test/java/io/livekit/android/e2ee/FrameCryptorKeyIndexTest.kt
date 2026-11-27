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

package io.livekit.android.e2ee

import io.livekit.android.room.Room
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.room.track.RemoteAudioTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.TrackPublication
import io.livekit.android.test.BaseTest
import io.livekit.android.webrtc.peerconnection.RTCThreadToken
import livekit.org.webrtc.FrameCryptor
import livekit.org.webrtc.FrameCryptorFactory
import livekit.org.webrtc.FrameCryptorKeyProvider
import livekit.org.webrtc.PeerConnectionFactory
import livekit.org.webrtc.RtpReceiver
import livekit.org.webrtc.RtpSender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FrameCryptorKeyIndexTest : BaseTest() {

    private val rtcKeyProvider = mock<FrameCryptorKeyProvider>()
    private val senderCryptor = mock<FrameCryptor>()
    private val secondSenderCryptor = mock<FrameCryptor>()
    private val receiverCryptor = mock<FrameCryptor>()
    private val dataPacketCryptorManager = RecordingDataPacketCryptorManager()
    private val rtcThreadToken = object : RTCThreadToken {
        override val isDisposed: Boolean = false
    }

    private fun mockedFactory() = mockStatic(FrameCryptorFactory::class.java).apply {
        `when`<FrameCryptorKeyProvider> {
            FrameCryptorFactory.createFrameCryptorKeyProvider(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        }.thenReturn(rtcKeyProvider)
        // First published track gets senderCryptor, the next secondSenderCryptor.
        `when`<FrameCryptor> {
            FrameCryptorFactory.createFrameCryptorForRtpSender(any(), any(), any(), any(), any())
        }.thenReturn(senderCryptor, secondSenderCryptor)
        `when`<FrameCryptor> {
            FrameCryptorFactory.createFrameCryptorForRtpReceiver(any(), any(), any(), any(), any())
        }.thenReturn(receiverCryptor)
    }

    private fun createProvider(sharedKey: Boolean = true): BaseKeyProvider {
        whenever(rtcKeyProvider.setSharedKey(any(), any())).thenReturn(true)
        whenever(rtcKeyProvider.ratchetSharedKey(any())).thenReturn(ByteArray(0))
        whenever(rtcKeyProvider.ratchetKey(any(), any())).thenReturn(ByteArray(0))
        return BaseKeyProvider(enableSharedKey = sharedKey)
    }

    private fun createManager(provider: KeyProvider): Triple<E2EEManager, Room, LocalParticipant> {
        val room = mock<Room>()
        val localParticipant = mock<LocalParticipant>()
        whenever(room.localParticipant).thenReturn(localParticipant)
        whenever(room.remoteParticipants).thenReturn(emptyMap())
        whenever(localParticipant.identity).thenReturn(Participant.Identity(LOCAL))
        whenever(localParticipant.trackPublications).thenReturn(emptyMap())

        val manager = E2EEManager(
            provider,
            mock<PeerConnectionFactory>(),
            object : DataPacketCryptorManager.Factory {
                override fun create(keyProvider: KeyProvider) = dataPacketCryptorManager
            },
            rtcThreadToken,
        )
        manager.setup(room) { }
        return Triple(manager, room, localParticipant)
    }

    private fun addLocalSender(
        manager: E2EEManager,
        room: Room,
        participant: LocalParticipant,
        sid: String = "sender-sid",
    ) {
        val track = mock<LocalAudioTrack>()
        whenever(track.sender).thenReturn(mock<RtpSender>())
        whenever(track.kind).thenReturn(Track.Kind.AUDIO)
        val publication = mock<TrackPublication>()
        whenever(publication.track).thenReturn(track)
        whenever(publication.sid).thenReturn(sid)

        manager.addPublishedTrack(track, publication, participant, room)
    }

    private fun addRemoteReceiver(manager: E2EEManager, room: Room) {
        val participant = mock<RemoteParticipant>()
        whenever(participant.identity).thenReturn(Participant.Identity(REMOTE))
        val track = mock<RemoteAudioTrack>()
        whenever(track.receiver).thenReturn(mock<RtpReceiver>())
        whenever(track.kind).thenReturn(Track.Kind.AUDIO)
        val publication = mock<TrackPublication>()
        whenever(publication.track).thenReturn(track)
        whenever(publication.sid).thenReturn("receiver-sid")

        manager.addSubscribedTrack(track, publication, participant, room)
    }

    /** Delegates to [BaseKeyProvider] and runs [onLatestRead] once, right after the
     *  first latest-index read: the point where a concurrent rotation can interleave
     *  with a new cryptor's initial stamp. */
    private class InterleavingKeyProvider(private val base: BaseKeyProvider) :
        KeyProvider by base,
        KeyIndexObservable by base {
        @Volatile
        var onLatestRead: (() -> Unit)? = null

        override fun getLatestKeyIndex(participantId: String): Int {
            val index = base.getLatestKeyIndex(participantId)
            onLatestRead?.let {
                onLatestRead = null
                it()
            }
            return index
        }
    }

    /** Mirrors the native wrapper: any call on a disposed FrameCryptor throws.
     *  [afterDispose] runs right after the cryptor is marked disposed: the window
     *  where a concurrent loop over the cryptor map can interleave with removal. */
    private fun throwWhenDisposed(cryptor: FrameCryptor, afterDispose: () -> Unit = {}): MutableList<String> {
        val callsAfterDispose = Collections.synchronizedList(mutableListOf<String>())
        var disposed = false
        doAnswer {
            if (disposed) {
                callsAfterDispose.add("setKeyIndex")
                throw IllegalStateException("FrameCryptor has been disposed.")
            }
            null
        }.whenever(cryptor).keyIndex = any()
        doAnswer {
            if (disposed) {
                callsAfterDispose.add("setEnabled")
                throw IllegalStateException("FrameCryptor has been disposed.")
            }
            null
        }.whenever(cryptor).isEnabled = any()
        doAnswer {
            if (disposed) {
                callsAfterDispose.add("dispose")
                throw IllegalStateException("FrameCryptor has been disposed.")
            }
            disposed = true
            afterDispose()
            null
        }.whenever(cryptor).dispose()
        return callsAfterDispose
    }

    // Participant.Identity is a value class; Mockito matchers can't match its
    // unboxed args, so record encrypt() calls with a plain fake instead.
    private class RecordingDataPacketCryptorManager : DataPacketCryptorManager {
        var lastEncryptKeyIndex: Int? = null

        override fun encrypt(
            participantId: Participant.Identity,
            keyIndex: Int,
            payload: ByteArray,
        ): EncryptedPacket? {
            lastEncryptKeyIndex = keyIndex
            return null
        }

        override fun decrypt(participantId: Participant.Identity, packet: EncryptedPacket): ByteArray? = null

        override fun dispose() {}
    }

    // --- Provider: index bookkeeping and notifications ---

    @Test
    fun sharedKeyInstallMovesLatestKeyIndex() {
        mockedFactory().use {
            val provider = createProvider()
            provider.setSharedKey("key", 3)
            assertEquals(3, provider.getLatestKeyIndex(LOCAL))
            assertEquals(3, provider.getLatestKeyIndex("anyone"))
        }
    }

    @Test
    fun failedSharedKeyInstallDoesNotMoveIndexOrNotify() {
        mockedFactory().use {
            val provider = createProvider()
            whenever(rtcKeyProvider.setSharedKey(eq(4), any())).thenReturn(false)
            val seen = mutableListOf<Pair<String?, Int>>()
            provider.addKeyIndexObserver(this) { id, index -> seen.add(id to index) }

            assertFalse(provider.setSharedKey("key", 4))
            assertEquals(0, provider.getLatestKeyIndex(LOCAL))
            assertTrue("no notification for a failed install: $seen", seen.isEmpty())
        }
    }

    @Test
    fun negativeSharedKeyIndexInstallsWithoutMovingIndexOrNotifying() {
        mockedFactory().use {
            val provider = createProvider()
            provider.setSharedKey("key-a", 5)
            val seen = mutableListOf<Pair<String?, Int>>()
            provider.addKeyIndexObserver(this) { id, index -> seen.add(id to index) }

            // Native fans shared installs out to every participant handler, each
            // resolving -1 to its own current slot, so a negative index is resolved
            // to the shared slot (5) first. It doesn't move the index senders use.
            provider.setSharedKey("key-b", -1)
            verify(rtcKeyProvider).setSharedKey(eq(5), eq("key-b".toByteArray()))
            verify(rtcKeyProvider, never()).setSharedKey(eq(-1), any())
            assertEquals(5, provider.getLatestKeyIndex(LOCAL))
            assertTrue("no re-stamp for an unmoved index: $seen", seen.isEmpty())
        }
    }

    @Test
    fun nonSharedSetKeyKeepsPerParticipantIndexes() {
        mockedFactory().use {
            val provider = createProvider(sharedKey = false)
            provider.setKey("local-key", LOCAL, 2)
            provider.setKey("remote-key", REMOTE, 7)

            // Unchanged from before: each participant keeps its own latest index,
            // so a remote install can't redirect the local sender.
            assertEquals(2, provider.getLatestKeyIndex(LOCAL))
            assertEquals(7, provider.getLatestKeyIndex(REMOTE))
        }
    }

    @Test
    fun observerSeesWhoseIndexChangedAndRemovalStopsCallbacks() {
        mockedFactory().use {
            val shared = createProvider()
            val seen = mutableListOf<Pair<String?, Int>>()
            shared.addKeyIndexObserver(this) { id, index -> seen.add(id to index) }
            shared.setSharedKey("k3", 3)
            shared.removeKeyIndexObserver(this)
            shared.setSharedKey("k4", 4)
            assertEquals(listOf<Pair<String?, Int>>(null to 3), seen)

            val perParticipant = createProvider(sharedKey = false)
            seen.clear()
            perParticipant.addKeyIndexObserver(this) { id, index -> seen.add(id to index) }
            perParticipant.setKey("k", REMOTE, 6)
            assertEquals(listOf<Pair<String?, Int>>(REMOTE to 6), seen)
        }
    }

    // --- Recorded indexes are the slots native actually writes ---

    @Test
    fun sharedKeyIndexPastTheRingWrapsToTheInstalledSlot() {
        mockedFactory().use {
            val provider = createProvider()
            val (manager, room, localParticipant) = createManager(provider)
            addLocalSender(manager, room, localParticipant)

            // Native installs index 20 into slot 20 % 16 = 4 and looks a sender's
            // stamp up without a bounds check, so the stamp must be 4, never 20.
            provider.setSharedKey("rotated", 20)
            assertEquals(4, provider.getLatestKeyIndex(LOCAL))
            verify(senderCryptor).keyIndex = 4
            verify(senderCryptor, never()).keyIndex = 20
        }
    }

    @Test
    fun perParticipantIndexPastTheRingWrapsToTheInstalledSlot() {
        mockedFactory().use {
            val provider = createProvider(sharedKey = false)
            val (manager, room, localParticipant) = createManager(provider)
            addLocalSender(manager, room, localParticipant)

            provider.setKey("rotated", LOCAL, 17)
            verify(rtcKeyProvider).setKey(eq(LOCAL), eq(17), eq("rotated".toByteArray()))
            assertEquals(1, provider.getLatestKeyIndex(LOCAL))
            verify(senderCryptor).keyIndex = 1
            verify(senderCryptor, never()).keyIndex = 17
        }
    }

    @Test
    fun negativePerParticipantInstallDoesNotMoveOrRestamp() {
        mockedFactory().use {
            val provider = createProvider(sharedKey = false)
            val (manager, room, localParticipant) = createManager(provider)
            addLocalSender(manager, room, localParticipant)
            provider.setKey("current", LOCAL, 2)
            verify(senderCryptor).keyIndex = 2

            // A negative index goes to native unchanged and doesn't move the stamped
            // index. Stamping -1 would write it into every frame's trailer and send
            // receivers to a slot that doesn't exist.
            provider.setKey("replacement", LOCAL, -1)
            verify(rtcKeyProvider).setKey(eq(LOCAL), eq(-1), eq("replacement".toByteArray()))
            assertEquals(2, provider.getLatestKeyIndex(LOCAL))
            verify(senderCryptor, never()).keyIndex = -1
        }
    }

    @Test
    fun perParticipantImplicitOperationsDeferToNativeCurrentSlot() {
        mockedFactory().use {
            // Per-participant operations reach only that participant's handler, whose
            // current pointer follows the slot their sender encrypts with (moved by an
            // explicit or auto ratchet). Negative installs and null-index ratchets go to
            // native unresolved so both land in that slot.
            val provider = createProvider(sharedKey = false)
            provider.setKey("k2", REMOTE, 2)
            provider.ratchetKey(REMOTE, 5)
            provider.setKey("replacement", REMOTE, -1)
            verify(rtcKeyProvider).setKey(eq(REMOTE), eq(-1), eq("replacement".toByteArray()))
            provider.ratchetKey(REMOTE, null)
            verify(rtcKeyProvider).ratchetKey(REMOTE, -1)
            assertEquals(2, provider.getLatestKeyIndex(REMOTE))
        }
    }

    @Test
    fun sharedImplicitOperationsNeverPassANegativeIndexToNative() {
        mockedFactory().use {
            // Native fans shared ratchets out to every participant handler with the
            // given index; -1 would resolve per handler, and a peer that auto-ratcheted
            // onto another slot would get the shared key's new material over its active
            // key. Shared operations resolve to the recorded shared slot instead.
            val provider = createProvider()
            provider.setSharedKey("k3", 3)
            provider.ratchetSharedKey(6)
            provider.setSharedKey("replacement", -1)
            verify(rtcKeyProvider).setSharedKey(eq(3), eq("replacement".toByteArray()))
            provider.ratchetSharedKey(null)
            provider.ratchetSharedKey(-1)
            verify(rtcKeyProvider, times(2)).ratchetSharedKey(3)
            verify(rtcKeyProvider, never()).ratchetSharedKey(-1)
            verify(rtcKeyProvider, never()).setSharedKey(eq(-1), any())
            assertEquals(3, provider.getLatestKeyIndex(LOCAL))
        }
    }

    @Test
    fun ringSizeIsClampedLikeNative() {
        mockedFactory().use {
            whenever(rtcKeyProvider.setSharedKey(any(), any())).thenReturn(true)

            // <= 0 falls back to the default ring (16).
            val zero = BaseKeyProvider(keyRingSize = 0)
            zero.setSharedKey("key", 17)
            assertEquals(1, zero.getLatestKeyIndex(LOCAL))

            // > 256 is capped at 256.
            val oversized = BaseKeyProvider(keyRingSize = 300)
            oversized.setSharedKey("key", 257)
            assertEquals(1, oversized.getLatestKeyIndex(LOCAL))
            oversized.setSharedKey("key", 255)
            assertEquals(255, oversized.getLatestKeyIndex(LOCAL))
        }
    }

    // --- Manager: stamping and re-stamping senders ---

    @Test
    fun newSendersAreStampedWithLatestSharedKeyIndex() {
        mockedFactory().use {
            val provider = createProvider()
            provider.setSharedKey("key", 4)

            val (manager, room, localParticipant) = createManager(provider)
            addLocalSender(manager, room, localParticipant)

            verify(senderCryptor).keyIndex = 4
        }
    }

    @Test
    fun existingSendersFollowSharedKeyRotation() {
        mockedFactory().use {
            val provider = createProvider()
            val (manager, room, localParticipant) = createManager(provider)
            addLocalSender(manager, room, localParticipant)

            provider.setSharedKey("rotated", 9)
            verify(senderCryptor).keyIndex = 9
        }
    }

    @Test
    fun senderPublishedAfterSharedKeyRotationUsesRotatedIndex() {
        mockedFactory().use {
            val provider = createProvider()
            val (manager, room, localParticipant) = createManager(provider)
            addLocalSender(manager, room, localParticipant, sid = "first")

            provider.setSharedKey("rotated", 2)
            verify(senderCryptor).keyIndex = 2

            // A track published after the rotation must start on the rotated
            // slot too; before the fix getLatestKeyIndex() returned 0 in shared
            // mode, so it encrypted with a key receivers had already moved off.
            addLocalSender(manager, room, localParticipant, sid = "second")
            verify(secondSenderCryptor).keyIndex = 2
            verify(secondSenderCryptor, never()).keyIndex = 0

            // Data packets take the same path.
            manager.encrypt(byteArrayOf(1, 2, 3))
            assertEquals(2, dataPacketCryptorManager.lastEncryptKeyIndex)
        }
    }

    @Test
    fun senderPublishedAfterLocalKeyRotationUsesRotatedIndex() {
        mockedFactory().use {
            val provider = createProvider(sharedKey = false)
            val (manager, room, localParticipant) = createManager(provider)
            addLocalSender(manager, room, localParticipant, sid = "first")

            provider.setKey("rotated", LOCAL, 3)
            verify(senderCryptor).keyIndex = 3

            addLocalSender(manager, room, localParticipant, sid = "second")
            verify(secondSenderCryptor).keyIndex = 3
        }
    }

    @Test
    fun remoteKeyInstallDoesNotRestampLocalSenders() {
        mockedFactory().use {
            val provider = createProvider(sharedKey = false)
            val (manager, room, localParticipant) = createManager(provider)
            addLocalSender(manager, room, localParticipant)

            provider.setKey("remote-key", REMOTE, 4)
            verify(senderCryptor, never()).keyIndex = 4

            provider.setKey("local-key", LOCAL, 5)
            verify(senderCryptor).keyIndex = 5
        }
    }

    @Test
    fun receiversAreNotRestamped() {
        mockedFactory().use {
            val provider = createProvider()
            val (manager, room, localParticipant) = createManager(provider)
            addLocalSender(manager, room, localParticipant)
            addRemoteReceiver(manager, room)

            provider.setSharedKey("rotated", 9)
            verify(senderCryptor).keyIndex = 9
            verify(receiverCryptor, never()).keyIndex = 9
        }
    }

    @Test
    fun cleanupStopsRestamping() {
        mockedFactory().use {
            val provider = createProvider()
            val (manager, room, localParticipant) = createManager(provider)
            addLocalSender(manager, room, localParticipant)

            manager.cleanup()
            provider.setSharedKey("after-cleanup", 6)
            verify(senderCryptor, never()).keyIndex = 6
        }
    }

    // --- Ratchet targets the slot senders use ---

    @Test
    fun managerRatchetAdvancesTheCurrentSharedSlot() {
        mockedFactory().use {
            val provider = createProvider()
            val (manager, _, _) = createManager(provider)
            provider.setSharedKey("rotated", 2)

            // Before the fix this ratcheted slot 0 (the interface default) while
            // senders kept encrypting with slot 2.
            manager.ratchetKey()
            verify(rtcKeyProvider).ratchetSharedKey(2)
            verify(rtcKeyProvider, never()).ratchetSharedKey(0)
        }
    }

    @Test
    fun ratchetWithExplicitOrDefaultIndexIsUnchanged() {
        mockedFactory().use {
            val provider = createProvider()
            provider.setSharedKey("rotated", 3)

            // The interface default (0) and explicit indexes keep their meaning.
            provider.ratchetSharedKey()
            verify(rtcKeyProvider).ratchetSharedKey(0)
            provider.ratchetSharedKey(5)
            verify(rtcKeyProvider).ratchetSharedKey(5)
        }
    }

    // --- Concurrency ---

    @Test
    fun rotationDuringSenderSetupIsNotOverwrittenByStaleIndex() {
        mockedFactory().use {
            val base = createProvider()
            base.setSharedKey("key", 2)
            val provider = InterleavingKeyProvider(base)
            val stamped = Collections.synchronizedList(mutableListOf<Int>())
            doAnswer { invocation ->
                stamped.add(invocation.getArgument(0))
                null
            }.whenever(senderCryptor).keyIndex = any()

            val (manager, room, localParticipant) = createManager(provider)

            // The sender has read index 2 but not stamped it yet; a rotation to 3
            // runs its re-stamp on another thread. Unsynchronized setup let that
            // re-stamp land first and then overwrote it with the stale 2.
            val rotation = Thread { base.setSharedKey("rotated", 3) }
            provider.onLatestRead = {
                rotation.start()
                // Give the re-stamp time to run if setup doesn't hold the lock; with
                // the lock held it blocks until setup finishes.
                rotation.join(300)
            }
            addLocalSender(manager, room, localParticipant)
            rotation.join(TimeUnit.SECONDS.toMillis(5))

            assertEquals(3, stamped.last())
        }
    }

    @Test
    fun removingASenderDuringRotationNeverTouchesTheDisposedCryptor() {
        mockedFactory().use {
            val provider = createProvider()
            val (manager, room, localParticipant) = createManager(provider)
            addLocalSender(manager, room, localParticipant)
            // A rotation arriving right after dispose: before the fix the disposed
            // cryptor was still in the map, so the re-stamp hit it and threw.
            val rotated = CountDownLatch(1)
            val callsAfterDispose = throwWhenDisposed(senderCryptor) {
                provider.setSharedKey("rotated", 5)
                rotated.countDown()
            }
            val publication = mock<TrackPublication>()
            whenever(publication.sid).thenReturn("sender-sid")
            manager.removePublishedTrack(mock<LocalAudioTrack>(), publication, localParticipant, room)

            assertTrue(rotated.await(5, TimeUnit.SECONDS))
            assertTrue("calls after dispose: $callsAfterDispose", callsAfterDispose.isEmpty())
        }
    }

    @Test
    fun removingAReceiverDuringAnEnabledToggleNeverTouchesTheDisposedCryptor() {
        mockedFactory().use {
            val provider = createProvider()
            val (manager, room, _) = createManager(provider)
            addRemoteReceiver(manager, room)
            // The enabled toggle walks every cryptor, receivers included.
            val toggled = CountDownLatch(1)
            val callsAfterDispose = throwWhenDisposed(receiverCryptor) {
                manager.enabled = false
                toggled.countDown()
            }
            val participant = mock<RemoteParticipant>()
            whenever(participant.identity).thenReturn(Participant.Identity(REMOTE))
            val publication = mock<TrackPublication>()
            whenever(publication.sid).thenReturn("receiver-sid")
            manager.removeSubscribedTrack(mock<RemoteAudioTrack>(), publication, participant, room)

            assertTrue(toggled.await(5, TimeUnit.SECONDS))
            assertTrue("calls after dispose: $callsAfterDispose", callsAfterDispose.isEmpty())
        }
    }

    private companion object {
        const val LOCAL = "local"
        const val REMOTE = "remote"
    }
}
