package io.livekit.android.room

import com.google.protobuf.util.JsonFormat
import io.livekit.android.BaseTest
import io.livekit.android.mock.MockWebSocketFactory
import io.livekit.android.mock.TestData
import io.livekit.android.util.toOkioByteString
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.serialization.json.Json
import livekit.LivekitModels
import livekit.LivekitRtc
import okhttp3.*
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.webrtc.SessionDescription

@ExperimentalCoroutinesApi
class SignalClientTest : BaseTest() {

    lateinit var wsFactory: MockWebSocketFactory
    lateinit var client: SignalClient

    @Mock
    lateinit var listener: SignalClient.Listener

    @Mock
    lateinit var okHttpClient: OkHttpClient

    @Before
    fun setup() {
        wsFactory = MockWebSocketFactory()
        client = SignalClient(
            wsFactory,
            JsonFormat.parser(),
            JsonFormat.printer(),
            Json,
            useJson = false,
            okHttpClient = okHttpClient,
            ioDispatcher = coroutineRule.dispatcher
        )
        client.listener = listener
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
    fun joinAndResponse() = runTest {
        println("dispatcher = ${this.coroutineContext}")
        val job = async {
            client.join(EXAMPLE_URL, "")
        }

        connectWebsocketAndJoin()

        val response = job.await()
        Assert.assertEquals(true, client.isConnected)
        Assert.assertEquals(response, JOIN.join)
    }

    @Test
    fun reconnect() = runTest {
        val job = async {
            client.reconnect(EXAMPLE_URL, "")
        }

        client.onOpen(wsFactory.ws, createOpenResponse(wsFactory.request))

        job.await()
        Assert.assertEquals(true, client.isConnected)
    }

    @Test
    fun joinFailure() = runTest {
        var failed = false
        val job = async {
            try {
                client.join(EXAMPLE_URL, "")
            } catch (e: Exception) {
                failed = true
            }
        }

        client.onFailure(wsFactory.ws, Exception(), null)
        job.await()

        Assert.assertTrue(failed)
    }

    @Test
    fun listenerNotCalledUntilOnReady() = runTest {
        val job = async {
            client.join(EXAMPLE_URL, "")
        }

        connectWebsocketAndJoin()
        client.onMessage(wsFactory.ws, OFFER.toOkioByteString())

        job.await()

        Mockito.verifyNoInteractions(listener)
    }

    @Test
    fun listenerCalledAfterOnReady() = runTest {
        val job = async {
            client.join(EXAMPLE_URL, "")
        }
        connectWebsocketAndJoin()
        client.onMessage(wsFactory.ws, OFFER.toOkioByteString())

        job.await()
        client.onReady()
        Mockito.verify(listener)
            .onOffer(argThat { type == SessionDescription.Type.OFFER && description == OFFER.offer.sdp })
    }

    /**
     * [WebSocketListener.onFailure] does not call through to
     * [WebSocketListener.onClosed]. Ensure that listener is called properly.
     */
    @Test
    fun listenerNotifiedAfterFailure() = runTest {
        val job = async {
            client.join(EXAMPLE_URL, "")
        }
        connectWebsocketAndJoin()
        job.await()

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
                sdp = "remote_offer"
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

        val REFRESH_TOKEN = with(LivekitRtc.SignalResponse.newBuilder()) {
            refreshToken = "refresh_token"
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