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

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.livekit.android.room.participant.AudioTrackPublishOptions
import io.livekit.android.room.track.Track
import io.livekit.android.test.MockE2ETest
import io.livekit.android.test.mock.TestData
import io.livekit.android.test.util.toPBByteString
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import livekit.LivekitRtc
import livekit.org.webrtc.PeerConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * Tests for concurrent publishes of the same track instance, e.g. the full
 * reconnect republish overlapping setMicrophoneEnabled, or a direct
 * publishAudioTrack overlapping setMicrophoneEnabled.
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class ConcurrentPublishMockE2ETest : MockE2ETest() {

    private fun grantAudioPermission() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Shadows.shadowOf(context as Application).grantPermissions(Manifest.permission.RECORD_AUDIO)
    }

    private fun reconnectWebsocket() {
        wsFactory.listener.onOpen(wsFactory.ws, createOpenResponse(wsFactory.request))
        val softReconnectParam = wsFactory.request.url
            .queryParameter(SignalClient.CONNECT_QUERY_RECONNECT)
            ?.toIntOrNull()
            ?: 0

        if (softReconnectParam == 0) {
            simulateMessageFromServer(TestData.JOIN)
        } else {
            simulateMessageFromServer(TestData.RECONNECT)
        }
    }

    private fun connectPublisherPeerConnection() {
        getPublisherPeerConnection().moveToIceConnectionState(PeerConnection.IceConnectionState.CONNECTED)
    }

    private fun deferNextAddTrackResponse(): () -> LivekitRtc.AddTrackRequest? {
        var deferredAddTrack: LivekitRtc.AddTrackRequest? = null
        wsFactory.registerSignalRequestHandler { request ->
            if (request.hasAddTrack() && deferredAddTrack == null) {
                deferredAddTrack = request.addTrack
                true
            } else {
                false
            }
        }
        return { deferredAddTrack }
    }

    private fun respondToAddTrack(addTrack: LivekitRtc.AddTrackRequest) {
        wsFactory.receiveMessage(
            with(LivekitRtc.SignalResponse.newBuilder()) {
                trackPublished = with(LivekitRtc.TrackPublishedResponse.newBuilder()) {
                    cid = addTrack.cid
                    track = TestData.LOCAL_AUDIO_TRACK
                    build()
                }
                build()
            },
        )
    }

    private fun sentAddTrackCount(): Int {
        return wsFactory.ws.sentRequests.count { requestString ->
            LivekitRtc.SignalRequest.newBuilder()
                .mergeFrom(requestString.toPBByteString())
                .build()
                .hasAddTrack()
        }
    }

    @Test
    fun micEnabledDuringReconnectSurvivesRepublish() = runTest {
        grantAudioPermission()
        room.setReconnectionType(ReconnectType.FORCE_FULL_RECONNECT)
        connect()

        assertTrue(room.localParticipant.setMicrophoneEnabled(true))
        assertTrue(room.localParticipant.setMicrophoneEnabled(false))

        disconnectPeerConnection()
        testScheduler.advanceTimeBy(1000)
        reconnectWebsocket()
        runCurrent()

        // App enables the mic mid-reconnect; the publish completes against the new session.
        assertTrue(room.localParticipant.setMicrophoneEnabled(true))
        val pub = room.localParticipant.getTrackPublication(Track.Source.MICROPHONE)
        assertNotNull(pub)
        val micTrack = pub!!.track!!

        // Reconnect completes; republishTracks() runs against the old (muted) snapshot.
        connectPeerConnection()
        connectPublisherPeerConnection()
        advanceUntilIdle()

        // The mid-reconnect publication supersedes the snapshot and must survive.
        val finalPub = room.localParticipant.getTrackPublication(Track.Source.MICROPHONE)
        assertNotNull(finalPub)
        assertFalse(finalPub!!.muted)
        assertTrue(micTrack.enabled)
    }

    @Test
    fun micEnableWaitsForInFlightRepublish() = runTest {
        grantAudioPermission()
        room.setReconnectionType(ReconnectType.FORCE_FULL_RECONNECT)
        connect()

        assertTrue(room.localParticipant.setMicrophoneEnabled(true))
        val micTrack = room.localParticipant.getTrackPublication(Track.Source.MICROPHONE)!!.track!!

        disconnectPeerConnection()
        testScheduler.advanceTimeBy(1000)
        reconnectWebsocket()
        runCurrent()

        val deferredAddTrack = deferNextAddTrackResponse()

        connectPeerConnection()
        connectPublisherPeerConnection()
        runCurrent()
        assertNotNull("republish addTrack should be in flight", deferredAddTrack())

        // The app's enable must wait for the in-flight republish instead of
        // failing as a duplicate and stopping the shared track.
        var micResult: Boolean? = null
        val micJob = launch {
            micResult = room.localParticipant.setMicrophoneEnabled(true)
        }
        runCurrent()
        assertNull(micResult)

        respondToAddTrack(deferredAddTrack()!!)
        runCurrent()
        micJob.join()

        assertEquals(true, micResult)
        val finalPub = room.localParticipant.getTrackPublication(Track.Source.MICROPHONE)
        assertNotNull(finalPub)
        assertFalse(finalPub!!.muted)
        assertTrue(micTrack.enabled)
    }

    @Test
    fun republishSkipsTrackPublishedByConcurrentEnable() = runTest {
        grantAudioPermission()
        room.setReconnectionType(ReconnectType.FORCE_FULL_RECONNECT)
        connect()

        assertTrue(room.localParticipant.setMicrophoneEnabled(true))
        val micTrack = room.localParticipant.getTrackPublication(Track.Source.MICROPHONE)!!.track!!

        disconnectPeerConnection()
        testScheduler.advanceTimeBy(1000)
        reconnectWebsocket()
        runCurrent()

        val deferredAddTrack = deferNextAddTrackResponse()

        var micResult: Boolean? = null
        val micJob = launch {
            micResult = room.localParticipant.setMicrophoneEnabled(true)
        }
        runCurrent()
        assertNotNull("app addTrack should be in flight", deferredAddTrack())

        // Reconnect completes while the app's publish is in flight.
        connectPeerConnection()
        connectPublisherPeerConnection()
        runCurrent()

        respondToAddTrack(deferredAddTrack()!!)
        runCurrent()
        micJob.join()
        advanceUntilIdle()

        assertEquals(true, micResult)
        val finalPub = room.localParticipant.getTrackPublication(Track.Source.MICROPHONE)
        assertNotNull(finalPub)
        assertFalse(finalPub!!.muted)
        assertTrue(micTrack.enabled)

        // The republish must skip the superseded snapshot entry rather than
        // republishing or stopping the app's live track.
        assertEquals(1, sentAddTrackCount())
    }

    @Test
    fun micEnabledBetweenReconnectAttemptsSurvives() = runTest {
        grantAudioPermission()
        room.setReconnectionType(ReconnectType.FORCE_FULL_RECONNECT)
        connect()

        assertTrue(room.localParticipant.setMicrophoneEnabled(true))
        assertTrue(room.localParticipant.setMicrophoneEnabled(false))

        disconnectPeerConnection()
        testScheduler.advanceTimeBy(1000)
        reconnectWebsocket()
        runCurrent()

        // App enables the mic during reconnect attempt 1; the publish lands on
        // attempt 1's session.
        assertTrue(room.localParticipant.setMicrophoneEnabled(true))
        val micTrack = room.localParticipant.getTrackPublication(Track.Source.MICROPHONE)!!.track!!

        // Attempt 1 fails at the ICE wait; attempt 2 starts and joins.
        testScheduler.advanceTimeBy(25_000)
        reconnectWebsocket()
        runCurrent()
        connectPeerConnection()
        connectPublisherPeerConnection()
        advanceUntilIdle()

        // The publication created between attempts must be restored on attempt 2.
        val finalPub = room.localParticipant.getTrackPublication(Track.Source.MICROPHONE)
        assertNotNull(finalPub)
        assertFalse(finalPub!!.muted)
        assertTrue(micTrack.enabled)
    }

    @Test
    fun micMutedDuringReconnectStaysMutedAcrossAttempts() = runTest {
        grantAudioPermission()
        room.setReconnectionType(ReconnectType.FORCE_FULL_RECONNECT)
        connect()

        assertTrue(room.localParticipant.setMicrophoneEnabled(true))
        val micTrack = room.localParticipant.getTrackPublication(Track.Source.MICROPHONE)!!.track!!

        disconnectPeerConnection()
        testScheduler.advanceTimeBy(1000)
        reconnectWebsocket()
        runCurrent()

        // App re-enables during attempt 1, then mutes; the mute is the newest state.
        assertTrue(room.localParticipant.setMicrophoneEnabled(true))
        assertTrue(room.localParticipant.setMicrophoneEnabled(false))

        // Attempt 1 fails at the ICE wait; attempt 2 joins and republishes.
        testScheduler.advanceTimeBy(25_000)
        reconnectWebsocket()
        runCurrent()
        connectPeerConnection()
        connectPublisherPeerConnection()
        advanceUntilIdle()

        // The mute must win over the older unmuted snapshot entry: nothing is
        // republished and the track stays disabled.
        assertNull(room.localParticipant.getTrackPublication(Track.Source.MICROPHONE))
        assertFalse(micTrack.enabled)

        // Enabling afterwards publishes fresh and reactivates the track.
        assertTrue(room.localParticipant.setMicrophoneEnabled(true))
        val finalPub = room.localParticipant.getTrackPublication(Track.Source.MICROPHONE)
        assertNotNull(finalPub)
        assertFalse(finalPub!!.muted)
        assertTrue(micTrack.enabled)
    }

    @Test
    fun republishRestartsTrackStoppedByFailedEnable() = runTest {
        grantAudioPermission()
        room.setReconnectionType(ReconnectType.FORCE_FULL_RECONNECT)
        connect()

        assertTrue(room.localParticipant.setMicrophoneEnabled(true))
        val micTrack = room.localParticipant.getTrackPublication(Track.Source.MICROPHONE)!!.track!!

        disconnectPeerConnection()
        testScheduler.advanceTimeBy(1000)
        reconnectWebsocket()
        runCurrent()

        // The app's enable gets no response and dies on the add track deadline,
        // stopping the shared track on its failure path.
        val deferredAddTrack = deferNextAddTrackResponse()
        testScheduler.advanceTimeBy(500)
        var micResult: Boolean? = null
        val micJob = launch {
            micResult = room.localParticipant.setMicrophoneEnabled(true)
        }
        runCurrent()
        assertNotNull(deferredAddTrack())

        testScheduler.advanceTimeBy(25_000)
        micJob.join()
        assertEquals(false, micResult)
        assertFalse(micTrack.enabled)

        // Attempt 1 failed at the ICE wait meanwhile; attempt 2 joins and republishes.
        reconnectWebsocket()
        runCurrent()
        connectPeerConnection()
        connectPublisherPeerConnection()
        advanceUntilIdle()

        // The republished track must be live, not stopped.
        val finalPub = room.localParticipant.getTrackPublication(Track.Source.MICROPHONE)
        assertNotNull(finalPub)
        assertFalse(finalPub!!.muted)
        assertTrue(micTrack.enabled)
    }

    @Test
    fun consumerStoppedTrackStaysStoppedAcrossReconnect() = runTest {
        grantAudioPermission()
        room.setReconnectionType(ReconnectType.FORCE_FULL_RECONNECT)
        connect()

        assertTrue(room.localParticipant.setMicrophoneEnabled(true))
        val micTrack = room.localParticipant.getTrackPublication(Track.Source.MICROPHONE)!!.track!!

        // App stops the track through the public Track API without muting.
        micTrack.stop()
        assertFalse(micTrack.enabled)

        disconnectPeerConnection()
        testScheduler.advanceTimeBy(1000)
        reconnectWebsocket()
        runCurrent()
        connectPeerConnection()
        connectPublisherPeerConnection()
        advanceUntilIdle()

        // The republish must restore the app's exact state: publication present
        // and unmuted, track left stopped.
        val finalPub = room.localParticipant.getTrackPublication(Track.Source.MICROPHONE)
        assertNotNull(finalPub)
        assertFalse(finalPub!!.muted)
        assertFalse(micTrack.enabled)
    }

    @Test
    fun consumerStopAfterRecoveredEnableFailureStaysStopped() = runTest {
        grantAudioPermission()
        room.setReconnectionType(ReconnectType.FORCE_FULL_RECONNECT)
        connect()

        // The first enable dies on the add track deadline, stopping the track and
        // marking it as SDK-stopped.
        val deferredAddTrack = deferNextAddTrackResponse()
        var firstEnable: Boolean? = null
        val firstEnableJob = launch {
            firstEnable = room.localParticipant.setMicrophoneEnabled(true)
        }
        runCurrent()
        assertNotNull(deferredAddTrack())
        testScheduler.advanceTimeBy(21_000)
        firstEnableJob.join()
        assertEquals(false, firstEnable)

        // The retry succeeds; the marker no longer applies.
        assertTrue(room.localParticipant.setMicrophoneEnabled(true))
        val micTrack = room.localParticipant.getTrackPublication(Track.Source.MICROPHONE)!!.track!!
        assertTrue(micTrack.enabled)

        // App stops the track through the public Track API without muting.
        micTrack.stop()
        assertFalse(micTrack.enabled)

        disconnectPeerConnection()
        testScheduler.advanceTimeBy(1000)
        reconnectWebsocket()
        runCurrent()
        connectPeerConnection()
        connectPublisherPeerConnection()
        advanceUntilIdle()

        // The stale failure marker must not re-enable the consumer-stopped track.
        val finalPub = room.localParticipant.getTrackPublication(Track.Source.MICROPHONE)
        assertNotNull(finalPub)
        assertFalse(finalPub!!.muted)
        assertFalse(micTrack.enabled)
    }

    @Test
    fun consumerStopAfterDirectPublishRecoveryStaysStopped() = runTest {
        grantAudioPermission()
        room.setReconnectionType(ReconnectType.FORCE_FULL_RECONNECT)
        connect()

        // The enable dies on the add track deadline, stopping the track and
        // marking it as SDK-stopped.
        val deferredAddTrack = deferNextAddTrackResponse()
        var enableResult: Boolean? = null
        val enableJob = launch {
            enableResult = room.localParticipant.setMicrophoneEnabled(true)
        }
        runCurrent()
        assertNotNull(deferredAddTrack())
        testScheduler.advanceTimeBy(21_000)
        enableJob.join()
        assertEquals(false, enableResult)

        // App recovers by starting the track and publishing it directly; the
        // marker no longer applies.
        val micTrack = room.localParticipant.getOrCreateDefaultAudioTrack()
        micTrack.start()
        assertTrue(room.localParticipant.publishAudioTrack(micTrack))
        assertTrue(micTrack.enabled)

        // App stops the track through the public Track API without muting.
        micTrack.stop()
        assertFalse(micTrack.enabled)

        disconnectPeerConnection()
        testScheduler.advanceTimeBy(1000)
        reconnectWebsocket()
        runCurrent()
        connectPeerConnection()
        connectPublisherPeerConnection()
        advanceUntilIdle()

        // The stale failure marker must not re-enable the consumer-stopped track.
        val finalPub = room.localParticipant.getTrackPublication(Track.Source.MICROPHONE)
        assertNotNull(finalPub)
        assertFalse(finalPub!!.muted)
        assertFalse(micTrack.enabled)
    }

    @Test
    fun consumerStopAfterCrossSourceFailureMarkerStaysStopped() = runTest {
        grantAudioPermission()
        room.setReconnectionType(ReconnectType.FORCE_FULL_RECONNECT)
        connect()

        // Publish the default track under a different source; withhold the response.
        val deferredAddTrack = deferNextAddTrackResponse()
        val micTrack = room.localParticipant.getOrCreateDefaultAudioTrack()
        micTrack.start()
        var publishResult: Boolean? = null
        val publishJob = launch {
            publishResult = room.localParticipant.publishAudioTrack(
                micTrack,
                AudioTrackPublishOptions(source = Track.Source.SCREEN_SHARE_AUDIO),
            )
        }
        runCurrent()
        assertNotNull(deferredAddTrack())

        // A concurrent enable under the microphone lock fails on the duplicate cid,
        // stopping the track and recording a marker newer than the publish.
        var micResult: Boolean? = null
        val micJob = launch {
            micResult = room.localParticipant.setMicrophoneEnabled(true)
        }
        runCurrent()
        micJob.join()
        assertEquals(false, micResult)
        assertFalse(micTrack.enabled)

        // The older publish completes; its clear must not erase the newer marker.
        respondToAddTrack(deferredAddTrack()!!)
        runCurrent()
        publishJob.join()
        assertEquals(true, publishResult)

        // Consumer recovers through the public Track API, then intentionally stops.
        micTrack.start()
        micTrack.stop()
        assertFalse(micTrack.enabled)

        disconnectPeerConnection()
        testScheduler.advanceTimeBy(1000)
        reconnectWebsocket()
        runCurrent()
        connectPeerConnection()
        connectPublisherPeerConnection()
        advanceUntilIdle()

        // The marker predates the consumer's transitions and must not re-enable
        // the track.
        val finalPub = room.localParticipant.getTrackPublication(Track.Source.MICROPHONE)
        assertNotNull(finalPub)
        assertFalse(finalPub!!.muted)
        assertFalse(micTrack.enabled)
    }

    @Test
    fun enabledStateRevisionGuardsStaleTransitions() = runTest {
        grantAudioPermission()
        connect()

        val micTrack = room.localParticipant.getOrCreateDefaultAudioTrack()
        micTrack.start()
        val staleRevision = micTrack.enabledStateRevision.get()

        // A transition after the snapshot invalidates it.
        micTrack.stop()
        assertNull(micTrack.setEnabledIfRevisionUnchanged(staleRevision, true))
        assertFalse(micTrack.enabled)

        // An unchanged revision allows the transition.
        val currentRevision = micTrack.enabledStateRevision.get()
        assertNotNull(micTrack.setEnabledIfRevisionUnchanged(currentRevision, true))
        assertTrue(micTrack.enabled)

        // A stop's returned revision is that of the stop mutation itself, and any
        // later transition advances past it.
        val stopRevision = micTrack.stopReturningRevision()
        assertEquals(stopRevision, micTrack.enabledStateRevision.get())
        micTrack.start()
        assertTrue(micTrack.enabledStateRevision.get() > stopRevision)
    }

    @Test
    fun micEnableSerializesWithDirectPublish() = runTest {
        grantAudioPermission()
        connect()

        val deferredAddTrack = deferNextAddTrackResponse()

        val micTrack = room.localParticipant.getOrCreateDefaultAudioTrack()
        var publishResult: Boolean? = null
        val publishJob = launch {
            publishResult = room.localParticipant.publishAudioTrack(micTrack)
        }
        runCurrent()
        assertNotNull("direct publish addTrack should be in flight", deferredAddTrack())

        var micResult: Boolean? = null
        val micJob = launch {
            micResult = room.localParticipant.setMicrophoneEnabled(true)
        }
        runCurrent()
        assertNull(micResult)

        respondToAddTrack(deferredAddTrack()!!)
        runCurrent()
        publishJob.join()
        micJob.join()

        assertEquals(true, publishResult)
        assertEquals(true, micResult)
        val finalPub = room.localParticipant.getTrackPublication(Track.Source.MICROPHONE)
        assertNotNull(finalPub)
        assertFalse(finalPub!!.muted)
        assertTrue(micTrack.enabled)
    }
}
