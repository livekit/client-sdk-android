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
import org.junit.Test
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class FrameCryptorKeyIndexTest : BaseTest() {

    private val rtcKeyProvider = mock<FrameCryptorKeyProvider>()
    private val senderCryptor = mock<FrameCryptor>()
    private val receiverCryptor = mock<FrameCryptor>()
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
                override fun create(keyProvider: KeyProvider) = mock<DataPacketCryptorManager>()
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
}
