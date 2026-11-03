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

package io.livekit.android.room

import io.livekit.android.test.mock.MockRTCThreadToken
import io.livekit.android.webrtc.peerconnection.RTCThreadToken
import io.livekit.android.webrtc.peerconnection.executeBlockingOnRTCThread
import livekit.LivekitRtc.SignalTarget
import livekit.org.webrtc.DataChannel
import livekit.org.webrtc.IceCandidate
import livekit.org.webrtc.MediaStream
import livekit.org.webrtc.MediaStreamTrack
import livekit.org.webrtc.PeerConnection.PeerConnectionState
import livekit.org.webrtc.RtpReceiver
import livekit.org.webrtc.RtpTransceiver
import livekit.org.webrtc.SessionDescription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class DuplexTransportObserverTest {

    private val disposedToken = object : RTCThreadToken {
        override val isDisposed: Boolean = true
    }

    private lateinit var engine: RTCEngine
    private lateinit var engineListener: RTCEngine.Listener
    private lateinit var client: SignalClient
    private lateinit var observer: DuplexTransportObserver

    private val track = mock<MediaStreamTrack>()
    private val receiver = mock<RtpReceiver> { on { track() } doReturn track }
    private val streams = arrayOf(mock<MediaStream>())

    @Before
    fun setup() {
        engineListener = mock()
        engine = mock()
        whenever(engine.listener).thenReturn(engineListener)
        client = mock()
        observer = DuplexTransportObserver(engine, client, MockRTCThreadToken())
    }

    /**
     * Callbacks run asynchronously on the single RTC thread; a blocking no-op queued
     * behind them returns once they have all run.
     */
    private fun flushRTCThread() {
        executeBlockingOnRTCThread(MockRTCThreadToken()) {}
    }

    // region onAddTrack

    @Test
    fun addedTrackIsForwardedToEngine() {
        observer.onAddTrack(receiver, streams)
        flushRTCThread()

        verify(engineListener).onAddTrack(receiver, track, streams)
    }

    @Test
    fun addedTrackWithoutMediaTrackIsIgnored() {
        val emptyReceiver = mock<RtpReceiver> { on { track() } doReturn null }

        observer.onAddTrack(emptyReceiver, streams)
        flushRTCThread()

        verify(engineListener, never()).onAddTrack(any(), any(), any())
    }

    @Test
    fun addedTrackWithoutReceiverIsIgnored() {
        observer.onAddTrack(null, streams)
        flushRTCThread()

        verify(engineListener, never()).onAddTrack(any(), any(), any())
    }

    @Test
    fun addedTrackWithoutStreamsIsIgnored() {
        observer.onAddTrack(receiver, null)
        flushRTCThread()

        verify(engineListener, never()).onAddTrack(any(), any(), any())
    }

    @Test
    fun addedTrackWithoutEngineListenerIsDropped() {
        whenever(engine.listener).thenReturn(null)

        observer.onAddTrack(receiver, streams)
        flushRTCThread()
    }

    @Test
    fun addedTrackAfterDisposeIsDropped() {
        val disposedObserver = DuplexTransportObserver(engine, client, disposedToken)

        disposedObserver.onAddTrack(receiver, streams)
        flushRTCThread()

        verify(engineListener, never()).onAddTrack(any(), any(), any())
    }

    // endregion

    // region onDataChannel

    @Test
    fun dataChannelIsForwardedToListener() {
        val channel = mock<DataChannel>()
        val received = mutableListOf<DataChannel>()
        observer.dataChannelListener = { received.add(it) }

        observer.onDataChannel(channel)
        flushRTCThread()

        assertEquals(listOf(channel), received)
    }

    @Test
    fun nullDataChannelIsIgnored() {
        val received = mutableListOf<DataChannel>()
        observer.dataChannelListener = { received.add(it) }

        observer.onDataChannel(null)
        flushRTCThread()

        assertEquals(emptyList<DataChannel>(), received)
    }

    @Test
    fun dataChannelWithoutListenerIsDropped() {
        observer.onDataChannel(mock())
        flushRTCThread()
    }

    // endregion

    // region onTrack

    @Test
    fun trackOfAnyMediaTypeIsAccepted() {
        val audio = mock<RtpTransceiver> { on { mediaType } doReturn MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO }
        val video = mock<RtpTransceiver> { on { mediaType } doReturn MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }

        observer.onTrack(audio)
        observer.onTrack(video)
        observer.onTrack(null)
    }

    // endregion

    // region publisher behavior

    @Test
    fun localCandidateIsSentForPublisher() {
        val candidate = IceCandidate("0", 0, "candidate:1 1 udp 2122260223 192.168.1.2 50000 typ host")

        observer.onIceCandidate(candidate)
        flushRTCThread()

        verify(client).sendCandidate(candidate, SignalTarget.PUBLISHER)
    }

    @Test
    fun offerIsSentToServer() {
        val offer = SessionDescription(SessionDescription.Type.OFFER, "local_offer")

        observer.onOffer(offer, offerId = 3)
        flushRTCThread()

        verify(client).sendOffer(offer, 3)
    }

    @Test
    fun renegotiationNeededNegotiates() {
        observer.onRenegotiationNeeded()
        flushRTCThread()

        verify(engine).negotiatePublisher()
    }

    @Test
    fun connectionChangeUpdatesState() {
        val states = mutableListOf<PeerConnectionState>()
        observer.connectionChangeListener = { states.add(it) }

        observer.onConnectionChange(PeerConnectionState.CONNECTED)
        flushRTCThread()

        assertSame(PeerConnectionState.CONNECTED, observer.connectionState)
        assertEquals(listOf(PeerConnectionState.CONNECTED), states)
    }

    // endregion
}
