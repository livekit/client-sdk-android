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

package io.livekit.android.telemetry

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Intent
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import io.livekit.android.LiveKit
import io.livekit.android.room.ReconnectType
import io.livekit.android.room.SignalClient
import io.livekit.android.test.MockE2ETest
import io.livekit.android.test.mock.MockAudioStreamTrack
import io.livekit.android.test.mock.MockMediaStream
import io.livekit.android.test.mock.MockRtpReceiver
import io.livekit.android.test.mock.TestData
import io.livekit.android.test.mock.createMediaStreamId
import io.livekit.android.test.mock.room.track.createMockLocalAudioTrack
import io.livekit.android.util.LKLog
import io.livekit.uniffi.telemetryDiagnostics
import io.livekit.uniffi.telemetryFlush
import io.livekit.uniffi.telemetryScope
import io.livekit.uniffi.telemetryStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import livekit.LivekitRtc
import livekit.org.webrtc.RTCStats
import livekit.org.webrtc.RTCStatsReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import uniffi.livekit_telemetry.AudioOutput
import uniffi.livekit_telemetry.AudioRouteReason
import uniffi.livekit_telemetry.DeviceEvent
import java.io.File
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

/**
 * End to end through the Rust core into a local OpenTelemetry collector, on the SDK's mocks (mock
 * websocket, mock peer connections): `otelcol-contrib --config src/test/resources/telemetry/otelcol.yaml`
 * writes every OTLP request as a JSON line to [COLLECTOR_OUTPUT], `LK_TELEMETRY_ENDPOINT=http://127.0.0.1:4319`
 * points the core at it, and a host build of `livekit_uniffi` runs it (`-PlivekitUniffiLibraryPath=…`).
 * Skipped when any of them is missing. The pipeline is process-wide, hence one story.
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class TelemetryMockE2ETest : MockE2ETest() {
    /** Unix nanoseconds when the pipeline started: the device instrument reports its initial state then. */
    private var startNs = 0L

    /** The pipeline is (re)installed before [mocksSetup] creates the Room, as the first Room of an app would. */
    @get:Rule
    val telemetryRule = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("LK_TELEMETRY_ENDPOINT points the core at a local collector", System.getenv("LK_TELEMETRY_ENDPOINT") != null)
                assumeTrue("a collector listens on :4319", runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", 4319), 500) } }.isSuccess)
                startNs = System.currentTimeMillis() * 1_000_000
                val app = ApplicationProvider.getApplicationContext<Application>()
                // The OS's sticky battery broadcast, which the device instrument reads when it starts.
                app.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_LEVEL, 42).putExtra(BatteryManager.EXTRA_SCALE, 100))
                assumeTrue("the pipeline starts (is livekit_uniffi on jna.library.path?)", Telemetry.configure(app))
                base.evaluate()
            }
        }
    }

    /**
     * One call and its aftermath: device changes → connect (a remote microphone announced by the
     * join) → publish → subscribe to first media → app data and warnings → quick reconnect →
     * disconnect → opt-out.
     */
    @Test
    fun aCallFromConnectToOptOut() = runTest {
        val marker = UUID.randomUUID().toString()
        val traceId = checkNotNull(room.telemetryScope).traceId()
        room.setReconnectionType(ReconnectType.FORCE_SOFT_RECONNECT)
        postDeviceChanges()

        connect(
            TestData.JOIN.toBuilder()
                .apply { join = join.toBuilder().addOtherParticipants(TestData.REMOTE_PARTICIPANT).build() }
                .build(),
        )

        // App data: a correlation attribute on everything from now on (one set, one removed).
        room.setTelemetryAttribute("app.call_id", marker)
        room.setTelemetryAttribute("app.removed", marker)
        room.setTelemetryAttribute("app.removed", null)

        // Publish a mock microphone: the server's TrackPublished answer completes the request.
        val publish = launch { room.localParticipant.publishAudioTrack(createMockLocalAudioTrack()) }
        simulateMessageFromServer(TestData.LOCAL_TRACK_PUBLISHED)
        publish.join()

        // Subscribe: the announced remote microphone's track arrives, then its media, and ours leaves.
        room.onAddTrack(
            MockRtpReceiver.create(),
            MockAudioStreamTrack(id = REMOTE_TRACK_ID),
            arrayOf(MockMediaStream(id = createMediaStreamId(TestData.REMOTE_PARTICIPANT.sid, TestData.REMOTE_AUDIO_TRACK.sid))),
        )
        getSubscriberPeerConnection().statsReport = report(
            RTCStats(0, "inbound-rtp", "IN", mapOf("kind" to "audio", "trackIdentifier" to REMOTE_TRACK_ID, "bytesReceived" to BigInteger("1200"), "packetsReceived" to 10L)),
        )
        getPublisherPeerConnection().statsReport = report(
            RTCStats(0, "media-source", "MS", mapOf("kind" to "audio", "trackIdentifier" to TestData.LOCAL_TRACK_PUBLISHED.trackPublished.cid)),
            RTCStats(0, "outbound-rtp", "OUT", mapOf("kind" to "audio", "mediaSourceId" to "MS", "bytesSent" to BigInteger("800"), "packetsSent" to 8L)),
        )
        Thread.sleep(3000) // first media, at the core's 1 s polls

        // A Room handler's warning with no span in flight lands in the Room's session: the server
        // unpublishes a track we never had. Outside any Room it is the process's.
        val unknownSid = "TR_unknown_$marker"
        simulateMessageFromServer(
            LivekitRtc.SignalResponse.newBuilder()
                .setTrackUnpublished(LivekitRtc.TrackUnpublishedResponse.newBuilder().setTrackSid(unknownSid))
                .build(),
        )
        LKLog.e { "$marker process" }
        room.emitTelemetryEvent("e2e.checkpoint", mapOf("e2e.marker" to marker))

        // A quick reconnect on the mocks: the primary (subscriber) peer connection fails, the
        // websocket reconnects, ICE reconnects.
        disconnectPeerConnection()
        testScheduler.advanceTimeBy(1000)
        reconnectWebsocket()
        connectPeerConnection()
        testScheduler.advanceTimeBy(1000)
        Thread.sleep(1500) // a poll on the reconnected session

        room.disconnect()
        // The device state is pushed from the instrument's own dispatcher: wait until it has shipped.
        val otlp = flushed { file -> file.logs.any { it.eventName == "lk.device.thermal.changed" } }
        // The pipeline's own account, for a run whose records did not all arrive.
        val diagnostics = telemetryDiagnostics()
        println("telemetry: $diagnostics")
        val spans = otlp.spans.filter { it.traceId == traceId }
        val logs = otlp.logs.filter { it.traceId == traceId }

        // Connect: one span with the required checkpoints; the reconnect is its own span.
        val connect = spans.single("lk.connect")
        assertTrue(connect.events.toString(), connect.events.containsAll(listOf("ws_open", "signal", "join_recv", "pc_created", "engine", "pc_connected", "room_connected")))
        assertEquals("ok", connect.attributes["lk.outcome"])
        assertEquals("1", connect.attributes["lk.connect.attempt"])
        val reconnect = spans.single("lk.reconnect")
        assertEquals(reconnect.attributes.toString(), "subscriber_failed", reconnect.attributes["lk.reconnect.reason"])
        assertEquals("ok", reconnect.attributes["lk.outcome"])
        assertTrue(reconnect.events.toString(), "attempt 1 quick" in reconnect.events)

        // Publish, and the subscribe of a track the join announced: intent → subscribed → first media.
        val published = spans.single("lk.publish")
        assertEquals("ok", published.attributes["lk.outcome"])
        assertEquals(TestData.LOCAL_AUDIO_TRACK.sid, published.attributes["lk.track.sid"])
        assertEquals("microphone", published.attributes["lk.track.source"])
        val subscribes = spans.filter { it.name == "lk.subscribe" }.associateBy { it.attributes["lk.track.sid"] }
        val audio = checkNotNull(subscribes[TestData.REMOTE_AUDIO_TRACK.sid]) { "${subscribes.keys}" }
        assertEquals(audio.attributes.toString(), "ok", audio.attributes["lk.outcome"])
        assertEquals(TestData.REMOTE_PARTICIPANT.identity, audio.attributes["lk.participant.remote_identity"])
        assertEquals(listOf("subscribed", "first_media"), audio.events)
        // The announced camera never arrives: its intent is a span too, ended by the disconnect.
        val video = checkNotNull(subscribes[TestData.REMOTE_VIDEO_TRACK.sid]) { "${subscribes.keys}" }
        assertEquals(video.attributes.toString(), "cancelled", video.attributes["lk.outcome"])

        // RTC windows from one report per peer connection, each track in its direction.
        val windows = logs.filter { it.eventName == "lk.rtc.stats.sample" }
        for ((sid, direction) in listOf(TestData.LOCAL_AUDIO_TRACK.sid to "outbound", TestData.REMOTE_AUDIO_TRACK.sid to "inbound")) {
            val window = windows.any { it.attributes["lk.track.sid"] == sid && it.attributes["lk.track.direction"] == direction }
            assertTrue("$direction window: ${windows.map { it.attributes }}", window)
        }
        assertTrue("windows carry the correlation attribute", windows.all { it.attributes["app.call_id"] == marker })

        // App data and SDK records.
        assertTrue(logs.any { it.eventName == "custom.e2e.checkpoint" && it.attributes["e2e.marker"] == marker && it.attributes["app.call_id"] == marker })
        assertFalse(otlp.logs.any { it.attributes["app.removed"] != null })
        val handler = logs.singleOrNull { it.body?.endsWith(unknownSid) == true }
        assertTrue("a Room handler's warning: the Room's session, no span: $handler", handler != null && handler.spanId.isEmpty())
        assertEquals("roomname", handler!!.attributes["lk.room.name"])
        assertEquals(TestData.LOCAL_PARTICIPANT.identity, handler.attributes["lk.participant.identity"])
        val process = otlp.logs.singleOrNull { it.body == "$marker process" }
        assertTrue("outside any Room: the process scope", process != null && process.traceId != traceId)
        assertTrue("log records are warnings and errors only", otlp.logs.filter { it.eventName.isEmpty() }.all { it.severity >= SEVERITY_WARN })

        // The session ends once, never on a reconnect.
        val ended = logs.filter { it.eventName == "lk.room.disconnected" }
        assertEquals(ended.map { it.attributes }.toString(), listOf("client_initiated"), ended.map { it.attributes["lk.disconnect.reason"] })

        // Device: the state's initial values, what Robolectric can drive, and what it can only post.
        val device = otlp.logs.filter { it.eventName.startsWith("lk.device.") }
        for (event in listOf("thermal", "low_power", "network", "battery", "memory", "app_state").map { "lk.device.$it.changed" } + DEVICE_EVENTS) {
            assertTrue("$event: ${device.map { it.eventName }} ($diagnostics)", device.any { it.eventName == event })
        }
        assertTrue(device.any { it.eventName == "lk.device.memory.changed" && it.attributes.containsValue("critical") })
        assertTrue(device.any { it.eventName == "lk.device.app_state.changed" && it.attributes.containsValue("background") })
        val captures = device.filter { it.eventName == "lk.device.capture.failed" }.map { it.attributes["lk.device.capture.device"] to it.attributes["lk.device.capture.reason"] }
        assertTrue("$captures", captures.containsAll(listOf("camera" to "disconnected", "microphone" to "other")))
        val stats = checkNotNull(telemetryStats())
        assertEquals("the whole call shipped", 0uL, stats.dropped)

        expectOptOut(marker)
    }

    private suspend fun expectOptOut(marker: String) {
        // Opt-out: what was not yet sent is deleted, nothing is collected afterwards.
        val pending = component.roomFactory().create(context)
        pending.emitTelemetryEvent("$marker.pending")
        LiveKit.disableTelemetry()
        assertNull("the opt-out is in effect when it returns", telemetryScope())
        assertNull("a Room created after the opt-out collects nothing", component.roomFactory().create(context).telemetryScope)
        assertFalse("an unsent event is deleted, never uploaded", flushed().logs.any { it.eventName == "custom.$marker.pending" })
        assertEquals("the on-disk cache is purged", emptyList<String>(), Telemetry.storageDirectory(context).list()?.toList().orEmpty())
    }

    /** Ships what the core holds and reads back what the collector wrote since the pipeline started. */
    private suspend fun flushed(until: (OtlpFile) -> Boolean = { true }): OtlpFile {
        var file = OtlpFile(File(COLLECTOR_OUTPUT), since = startNs)
        for (attempt in 0 until 5) {
            withContext(Dispatchers.IO) {
                // Each flush uploads a few batches: repeat until none is left.
                for (i in 0 until 10) {
                    telemetryFlush()
                    if ((telemetryStats()?.cachedBatches ?: 0uL) == 0uL) break
                }
            }
            Thread.sleep(2000) // the collector's file write
            file = OtlpFile(File(COLLECTOR_OUTPUT), since = startNs)
            if (until(file)) break
        }
        return file
    }

    /** OS changes Robolectric can drive through the real callbacks, and events posted where it cannot. */
    private fun postDeviceChanges() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        app.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        TelemetryCameraEvents.onCameraDisconnected()
        telemetryMicrophoneFailed()
        // No audio switch in the mocks (NoAudioHandler): posted the way its listeners would.
        Telemetry.deviceEvent(DeviceEvent.AudioRouteChanged(listOf(AudioOutput.SPEAKER), AudioRouteReason.UNKNOWN))
        Telemetry.deviceEvent(DeviceEvent.AudioInterruption(began = true))
        Thread.sleep(500)
    }

    private fun reconnectWebsocket() {
        wsFactory.listener.onOpen(wsFactory.ws, createOpenResponse(wsFactory.request))
        val softReconnectParam = wsFactory.request.url.queryParameter(SignalClient.CONNECT_QUERY_RECONNECT)?.toIntOrNull() ?: 0
        simulateMessageFromServer(if (softReconnectParam == 0) TestData.JOIN else TestData.RECONNECT)
    }

    private fun report(vararg stats: RTCStats) = RTCStatsReport(0, stats.associateBy { it.id })

    private fun List<OtlpFile.Span>.single(name: String) =
        filter { it.name == name }.also { assertEquals("one $name: ${map { span -> span.name }}", 1, it.size) }.first()

    companion object {
        const val COLLECTOR_OUTPUT = "/tmp/livekit-telemetry-otlp.jsonl"
        private const val REMOTE_TRACK_ID = "remote_audio"
        private const val SEVERITY_WARN = 13
        private val DEVICE_EVENTS = listOf("lk.device.capture.failed", "lk.device.audio_route.changed", "lk.device.audio.interruption")
    }
}

/** What the collector wrote: OTLP/JSON, one export request per line, from [since] (Unix nanoseconds) on. */
class OtlpFile(file: File, since: Long) {
    class Log(val eventName: String, val body: String?, val traceId: String, val spanId: String, val severity: Int, val attributes: Map<String, String>)

    /** [events] are the span's checkpoints (`ws_open`, `first_media`, `attempt 1 quick`, ...). */
    class Span(val name: String, val traceId: String, val attributes: Map<String, String>, val events: List<String>)

    val logs = mutableListOf<Log>()
    val spans = mutableListOf<Span>()

    init {
        for (line in file.readLines()) {
            val request = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
            for (scope in request.children("resourceLogs", "scopeLogs")) {
                for (r in scope.array("logRecords").map { it.jsonObject }) {
                    if (r.long("timeUnixNano") < since) continue
                    logs += Log(
                        eventName = r.string("eventName") ?: "",
                        body = r["body"]?.jsonObject?.string("stringValue"),
                        traceId = r.string("traceId") ?: "",
                        spanId = r.string("spanId") ?: "",
                        severity = r["severityNumber"]?.jsonPrimitive?.intOrNull ?: 0,
                        attributes = attributes(r["attributes"]),
                    )
                }
            }
            for (scope in request.children("resourceSpans", "scopeSpans")) {
                for (s in scope.array("spans").map { it.jsonObject }) {
                    if (s.long("startTimeUnixNano") < since) continue
                    spans += Span(
                        name = s.string("name") ?: "",
                        traceId = s.string("traceId") ?: "",
                        attributes = attributes(s["attributes"]),
                        events = s.array("events").mapNotNull { it.jsonObject.string("name") },
                    )
                }
            }
        }
    }

    private fun JsonObject.children(resources: String, scopes: String) = array(resources).flatMap { it.jsonObject.array(scopes) }.map { it.jsonObject }

    private fun JsonObject.array(key: String): List<JsonElement> = this[key]?.jsonArray ?: emptyList()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.long(key: String): Long = string(key)?.toLongOrNull() ?: 0L

    /** OTLP/JSON attributes (`[{key, value: {stringValue | intValue | boolValue | doubleValue}}]`) as strings. */
    private fun attributes(value: JsonElement?): Map<String, String> =
        (value?.jsonArray ?: emptyList()).mapNotNull { pair ->
            val entry = pair.jsonObject
            val key = entry.string("key") ?: return@mapNotNull null
            val any = entry["value"]?.jsonObject ?: return@mapNotNull null
            val text = any.string("stringValue")
                ?: any.string("intValue")
                ?: any["boolValue"]?.jsonPrimitive?.booleanOrNull?.toString()
                ?: any.string("doubleValue")
                ?: return@mapNotNull null
            key to text
        }.toMap()
}
