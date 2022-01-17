package io.livekit.android.room

import com.google.protobuf.util.JsonFormat
import io.livekit.android.mock.MockWebSocketFactory
import io.livekit.android.mock.TestData
import io.livekit.android.util.toOkioByteString
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestCoroutineDispatcher
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import kotlinx.serialization.json.Json
import livekit.LivekitModels
import livekit.LivekitRtc
import okhttp3.*
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.webrtc.SessionDescription

@ExperimentalCoroutinesApi
class SignalClientTest {

    lateinit var wsFactory: MockWebSocketFactory
    lateinit var client: SignalClient
    lateinit var listener: SignalClient.Listener
    lateinit var okHttpClient: OkHttpClient

    lateinit var coroutineDispatcher: TestCoroutineDispatcher
    lateinit var coroutineScope: TestCoroutineScope

    @Before
    fun setup() {
        coroutineDispatcher = TestCoroutineDispatcher()
        coroutineScope = TestCoroutineScope(coroutineDispatcher)
        wsFactory = MockWebSocketFactory()
        okHttpClient = Mockito.mock(OkHttpClient::class.java)
        client = SignalClient(
            wsFactory,
            JsonFormat.parser(),
            JsonFormat.printer(),
            Json,
            useJson = false,
            okHttpClient = okHttpClient,
            ioDispatcher = coroutineDispatcher
        )
        listener = Mockito.mock(SignalClient.Listener::class.java)
        client.listener = listener
    }

    @After
    fun tearDown() {
        coroutineScope.cleanupTestCoroutines()
    }

    private fun createOpenResponse(request: Request): Response {
        return Response.Builder()
            .request(request)
            .code(200)
            .protocol(Protocol.HTTP_2)
            .message("")
            .build()
    }

    /**
     * Supply the needed websocket messages to finish a join call.
     */
    private fun connectWebsocketAndJoin() {
        client.onOpen(wsFactory.ws, createOpenResponse(wsFactory.request))
        client.onMessage(wsFactory.ws, JOIN.toOkioByteString())
    }

    @Test
    fun joinAndResponse() {
        val job = coroutineScope.async {
            client.join(EXAMPLE_URL, "")
        }

        connectWebsocketAndJoin()

        runBlockingTest {
            val response = job.await()
            Assert.assertEquals(response, JOIN.join)
        }
    }

    @Test
    fun reconnect() {
        val job = coroutineScope.async {
            client.reconnect(EXAMPLE_URL, "")
        }

        client.onOpen(wsFactory.ws, createOpenResponse(wsFactory.request))

        runBlockingTest {
            job.await()
        }
    }

    @Test
    fun joinFailure() {
        var failed = false
        val job = coroutineScope.async {
            try {
                client.join(EXAMPLE_URL, "")
            } catch (e: Exception) {
                failed = true
            }
        }

        client.onFailure(wsFactory.ws, Exception(), null)
        runBlockingTest { job.await() }

        Assert.assertTrue(failed)
    }

    @Test
    fun listenerNotCalledUntilOnReady() {
        val job = coroutineScope.async {
            client.join(EXAMPLE_URL, "")
        }

        connectWebsocketAndJoin()
        client.onMessage(wsFactory.ws, OFFER.toOkioByteString())

        runBlockingTest { job.await() }

        Mockito.verifyNoInteractions(listener)
    }

    @Test
    fun listenerCalledAfterOnReady() {
        val job = coroutineScope.async {
            client.join(EXAMPLE_URL, "")
        }
        connectWebsocketAndJoin()
        client.onMessage(wsFactory.ws, OFFER.toOkioByteString())

        runBlockingTest { job.await() }
        client.onReady()
        Mockito.verify(listener)
            .onOffer(argThat { type == SessionDescription.Type.OFFER && description == OFFER.offer.sdp })
    }

    /**
     * [WebSocketListener.onFailure] does not call through to
     * [WebSocketListener.onClosed]. Ensure that listener is called properly.
     */
    @Test
    fun listenerNotifiedAfterFailure() {
        val job = coroutineScope.async {
            client.join(EXAMPLE_URL, "")
        }
        connectWebsocketAndJoin()
        runBlockingTest { job.await() }

        client.onFailure(wsFactory.ws, Exception(), null)

        Mockito.verify(listener)
            .onClose(any(), any())
    }

    // mock data
    companion object {
        const val EXAMPLE_URL = "ws://www.example.com"

        val JOIN = with(LivekitRtc.SignalResponse.newBuilder()) {
            join = with(joinBuilder) {
                room = with(roomBuilder) {
                    name = "roomname"
                    sid = "room_sid"
                    build()
                }
                participant = TestData.LOCAL_PARTICIPANT
                subscriberPrimary = true
                serverVersion = "0.15.2"
                build()
            }
            build()
        }

        val OFFER = with(LivekitRtc.SignalResponse.newBuilder()) {
            offer = with(offerBuilder) {
                sdp = ""
                type = "offer"
                build()
            }
            build()
        }

        val ROOM_UPDATE = with(LivekitRtc.SignalResponse.newBuilder()) {
            roomUpdate = with(roomUpdateBuilder) {
                room = with(roomBuilder) {
                    metadata = "metadata"
                    build()
                }
                build()
            }
            build()
        }

        val TRACK_PUBLISHED = with(LivekitRtc.SignalResponse.newBuilder()) {
            trackPublished = with(trackPublishedBuilder) {
                track = TestData.REMOTE_AUDIO_TRACK
                build()
            }
            build()
        }

        val PARTICIPANT_JOIN = with(LivekitRtc.SignalResponse.newBuilder()) {
            update = with(LivekitRtc.ParticipantUpdate.newBuilder()) {
                addParticipants(TestData.REMOTE_PARTICIPANT)
                build()
            }
            build()
        }

        val PARTICIPANT_DISCONNECT = with(LivekitRtc.SignalResponse.newBuilder()) {
            update = with(LivekitRtc.ParticipantUpdate.newBuilder()) {
                val disconnectedParticipant = TestData.REMOTE_PARTICIPANT.toBuilder()
                    .setState(LivekitModels.ParticipantInfo.State.DISCONNECTED)
                    .build()

                addParticipants(disconnectedParticipant)
                build()
            }
            build()
        }

        val ACTIVE_SPEAKER_UPDATE = with(LivekitRtc.SignalResponse.newBuilder()) {
            speakersChanged = with(LivekitRtc.SpeakersChanged.newBuilder()) {
                addSpeakers(TestData.REMOTE_SPEAKER_INFO)
                build()
            }
            build()
        }

        val PARTICIPANT_METADATA_CHANGED = with(LivekitRtc.SignalResponse.newBuilder()) {
            update = with(LivekitRtc.ParticipantUpdate.newBuilder()) {
                val participantMetadataChanged = TestData.REMOTE_PARTICIPANT.toBuilder()
                    .setMetadata("changed_metadata")
                    .build()

                addParticipants(participantMetadataChanged)
                build()
            }
            build()
        }


        val CONNECTION_QUALITY = with(LivekitRtc.SignalResponse.newBuilder()) {
            connectionQuality = with(connectionQualityBuilder) {
                addUpdates(with(LivekitRtc.ConnectionQualityInfo.newBuilder()) {
                    participantSid = JOIN.join.participant.sid
                    quality = LivekitModels.ConnectionQuality.EXCELLENT
                    build()
                })
                build()
            }
            build()
        }

        val STREAM_STATE_UPDATE = with(LivekitRtc.SignalResponse.newBuilder()) {
            streamStateUpdate = with(LivekitRtc.StreamStateUpdate.newBuilder()) {
                addStreamStates(with(LivekitRtc.StreamStateInfo.newBuilder()) {
                    participantSid = TestData.REMOTE_PARTICIPANT.sid
                    trackSid = TestData.REMOTE_AUDIO_TRACK.sid
                    state = LivekitRtc.StreamState.ACTIVE
                    build()
                })
                build()
            }
            build()
        }

        val SUBSCRIPTION_PERMISSION_UPDATE = with(LivekitRtc.SignalResponse.newBuilder()) {
            subscriptionPermissionUpdate = with(LivekitRtc.SubscriptionPermissionUpdate.newBuilder()) {
                participantSid = TestData.REMOTE_PARTICIPANT.sid
                trackSid = TestData.REMOTE_AUDIO_TRACK.sid
                allowed = false
                build()
            }
            build()
        }
        val LEAVE = with(LivekitRtc.SignalResponse.newBuilder()) {
            leave = with(leaveBuilder) {
                build()
            }
            build()
        }
    }
}