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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
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
            )
        }.thenReturn(rtcKeyProvider)
        `when`<FrameCryptor> {
            FrameCryptorFactory.createFrameCryptorForRtpSender(any(), any(), any(), any(), any())
        }.thenReturn(senderCryptor)
        `when`<FrameCryptor> {
            FrameCryptorFactory.createFrameCryptorForRtpReceiver(any(), any(), any(), any(), any())
        }.thenReturn(receiverCryptor)
    }

    private fun createProvider(sharedKey: Boolean = true): BaseKeyProvider {
        whenever(rtcKeyProvider.setSharedKey(any(), any())).thenReturn(true)
        return BaseKeyProvider(enableSharedKey = sharedKey)
    }

    private fun createManager(provider: KeyProvider): Triple<E2EEManager, Room, LocalParticipant> {
        val room = mock<Room>()
        val localParticipant = mock<LocalParticipant>()
        whenever(room.localParticipant).thenReturn(localParticipant)
        whenever(room.remoteParticipants).thenReturn(emptyMap())
        whenever(localParticipant.identity).thenReturn(Participant.Identity("local"))
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

    private fun addLocalSender(manager: E2EEManager, room: Room, participant: LocalParticipant): FrameCryptor {
        val track = mock<LocalAudioTrack>()
        whenever(track.sender).thenReturn(mock<RtpSender>())
        whenever(track.kind).thenReturn(Track.Kind.AUDIO)
        val publication = mock<TrackPublication>()
        whenever(publication.track).thenReturn(track)
        whenever(publication.sid).thenReturn("sender-sid")

        manager.addPublishedTrack(track, publication, participant, room)
        return senderCryptor
    }

    /** Delegates to [BaseKeyProvider] and runs [onLatestRead] once, right after the
     *  first latest-index read — the point where a concurrent rotation can interleave
     *  with a new cryptor's initial stamp. */
    private class InterleavingKeyProvider(private val base: BaseKeyProvider) :
        KeyProvider by base,
        KeyIndexObservable by base {
        @Volatile
        var onLatestRead: (() -> Unit)? = null

        // Sender setup reads whichever path E2EEManager takes; fire the
        // interleave on either so the hook tracks the real read.
        private fun fireOnce(index: Int): Int {
            onLatestRead?.let {
                onLatestRead = null
                it()
            }
            return index
        }

        override val currentKeyIndex: Int
            get() = fireOnce(base.currentKeyIndex)

        override fun getLatestKeyIndex(participantId: String): Int =
            fireOnce(base.getLatestKeyIndex(participantId))
    }

    /** Mirrors the native wrapper: any call on a disposed FrameCryptor throws.
     *  [afterDispose] runs right after the cryptor is marked disposed — the window
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

    private fun addRemoteReceiver(manager: E2EEManager, room: Room): FrameCryptor {
        val participant = mock<RemoteParticipant>()
        whenever(participant.identity).thenReturn(Participant.Identity("remote"))
        val track = mock<RemoteAudioTrack>()
        whenever(track.receiver).thenReturn(mock<RtpReceiver>())
        whenever(track.kind).thenReturn(Track.Kind.AUDIO)
        val publication = mock<TrackPublication>()
        whenever(publication.track).thenReturn(track)
        whenever(publication.sid).thenReturn("receiver-sid")

        manager.addSubscribedTrack(track, publication, participant, room)
        return receiverCryptor
    }

    @Test
    fun sharedKeyInstallMovesCurrentKeyIndex() {
        mockedFactory().use {
            val provider = createProvider()
            provider.setSharedKey("key", 3)
            assertEquals(3, provider.currentKeyIndex)
            assertEquals(3, provider.getLatestKeyIndex("anyone"))
        }
    }

    @Test
    fun setCurrentKeyIndexWrapsModuloKeyRingSize() {
        mockedFactory().use {
            val provider = createProvider()
            provider.setCurrentKeyIndex(20)
            assertEquals(20 % 16, provider.currentKeyIndex)
        }
    }

    @Test
    fun nonSharedSetKeyDoesNotMoveCurrentKeyIndex() {
        mockedFactory().use {
            val provider = createProvider(sharedKey = false)
            // A remote participant's key install cannot redirect the local sender index.
            provider.setKey("key", "remote", 7)
            assertEquals(0, provider.currentKeyIndex)
            assertEquals(0, provider.getLatestKeyIndex("remote"))
        }
    }

    @Test
    fun observerSeesLatestIndexAndRemovalStopsCallbacks() {
        mockedFactory().use {
            val provider = createProvider()
            val seen = mutableListOf<Int>()
            provider.addKeyIndexObserver(this) { seen.add(it) }

            provider.setCurrentKeyIndex(3)
            provider.setCurrentKeyIndex(5)
            assertEquals(listOf(3, 5), seen)

            provider.removeKeyIndexObserver(this)
            provider.setCurrentKeyIndex(7)
            assertEquals(listOf(3, 5), seen)
        }
    }

    @Test
    fun newSendersAreStampedWithCurrentKeyIndex() {
        mockedFactory().use {
            val provider = createProvider()
            provider.setSharedKey("key", 4)

            val (manager, room, localParticipant) = createManager(provider)
            addLocalSender(manager, room, localParticipant)

            verify(senderCryptor).keyIndex = 4
        }
    }

    @Test
    fun existingSendersFollowKeyIndexChanges() {
        mockedFactory().use {
            val provider = createProvider()
            val (manager, room, localParticipant) = createManager(provider)
            addLocalSender(manager, room, localParticipant)

            provider.setSharedKey("rotated", 9)
            verify(senderCryptor).keyIndex = 9
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

            // The sender has read index 2 but not stamped it yet; a rotation to 3 runs
            // its re-stamp on another thread. Unsynchronized setup let that re-stamp
            // land first and then overwrote it with the stale 2.
            val rotation = Thread { base.setCurrentKeyIndex(3) }
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
                provider.setCurrentKeyIndex(5)
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
            whenever(participant.identity).thenReturn(Participant.Identity("remote"))
            val publication = mock<TrackPublication>()
            whenever(publication.sid).thenReturn("receiver-sid")
            manager.removeSubscribedTrack(mock<RemoteAudioTrack>(), publication, participant, room)

            assertTrue(toggled.await(5, TimeUnit.SECONDS))
            assertTrue("calls after dispose: $callsAfterDispose", callsAfterDispose.isEmpty())
        }
    }

    /** An observable provider whose legacy latest-index lookup disagrees with
     *  [currentKeyIndex] — the case [E2EEManager.localSenderKeyIndex] exists for. */
    private class DivergentProvider(private val base: BaseKeyProvider) :
        KeyProvider by base,
        KeyIndexObservable by base {
        override val currentKeyIndex: Int
            get() = 7
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

    @Test
    fun negativeSharedKeyIndexInstallsWithoutMovingCurrentKeyIndex() {
        mockedFactory().use {
            val provider = createProvider()
            provider.setSharedKey("key-a", 5)
            assertEquals(5, provider.currentKeyIndex)
            val seen = mutableListOf<Int>()
            provider.addKeyIndexObserver(this) { seen.add(it) }

            // Matches the native provider: a negative index installs into the
            // current slot without moving it, so the stamp pointer stays put.
            provider.setSharedKey("key-b", -1)
            assertEquals(5, provider.currentKeyIndex)
            assertTrue("no re-stamp for an unmoved index: $seen", seen.isEmpty())
            verify(rtcKeyProvider).setSharedKey(eq(-1), any())
        }
    }

    @Test
    fun zeroKeyRingSizeFallsBackToDefaultRing() {
        mockedFactory().use {
            whenever(rtcKeyProvider.setSharedKey(any(), any())).thenReturn(true)
            val provider = BaseKeyProvider(keyRingSize = 0)

            // Native clamps a non-positive size to the default ring (16), so the
            // local index must wrap by that, not divide by zero.
            provider.setSharedKey("key", 2)
            assertEquals(2, provider.currentKeyIndex)

            provider.setCurrentKeyIndex(17)
            assertEquals(17 % 16, provider.currentKeyIndex)
        }
    }

    @Test
    fun oversizedKeyRingSizeCapsAtMax() {
        mockedFactory().use {
            val provider = BaseKeyProvider(keyRingSize = 300)

            // Native caps the ring at 256; the local index wraps by the capped size.
            provider.setCurrentKeyIndex(257)
            assertEquals(257 % 256, provider.currentKeyIndex)
        }
    }

    @Test
    fun observableProvidersStampWithCurrentKeyIndex() {
        mockedFactory().use {
            val base = createProvider()
            val provider = DivergentProvider(base)
            val (manager, room, localParticipant) = createManager(provider)

            // getLatestKeyIndex() delegates to base and returns 0; the observable
            // contract says new senders must use currentKeyIndex (7).
            addLocalSender(manager, room, localParticipant)
            verify(senderCryptor).keyIndex = 7

            // Data packets take the same path.
            manager.encrypt(byteArrayOf(1, 2, 3))
            assertEquals(7, dataPacketCryptorManager.lastEncryptKeyIndex)

            // Receivers are a remote participant's cryptors: they keep the
            // per-participant lookup, not the local sender index.
            addRemoteReceiver(manager, room)
            verify(receiverCryptor).keyIndex = 0
        }
    }
}
