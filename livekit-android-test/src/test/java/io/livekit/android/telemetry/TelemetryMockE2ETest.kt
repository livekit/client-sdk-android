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

import androidx.test.core.app.ApplicationProvider
import io.livekit.android.room.ReconnectType
import io.livekit.android.room.SignalClient
import io.livekit.android.test.MockE2ETest
import io.livekit.android.test.mock.TestData
import io.livekit.android.test.mock.room.track.createMockLocalAudioTrack
import io.livekit.android.util.LKLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * End to end through the Rust core on the SDK's mocks: mock websocket, mock peer connections and
 * a mock local audio track. Needs the collector the Swift tests use — `otelcol-contrib --config
 * Tests/LiveKitCoreTests/Telemetry/otelcol-lgtm.yaml`, listening on :4319 and writing OTLP/JSON
 * lines to [COLLECTOR_OUTPUT] (the file is shared with other runs, so records are filtered by time,
 * never truncated: the collector keeps its write offset) — and a host build of `livekit_uniffi` on
 * `jna.library.path` (`-PlivekitUniffiLibraryPath=…`). Skipped when the collector is not there.
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class TelemetryMockE2ETest : MockE2ETest() {

    /** Unix nanoseconds when the pipeline started: the device instrument reports its initial state right then. */
    private var startNs = 0L

    /**
     * Telemetry is process-wide and a Room takes its scope at creation, so it is configured around
     * the whole test, before [mocksSetup] creates the Room, like an app would at launch.
     */
    @get:Rule
    val telemetryRule = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("collector on $COLLECTOR_ENDPOINT", collectorReachable())
                startNs = System.currentTimeMillis() * 1_000_000
                Telemetry.configure(
                    ApplicationProvider.getApplicationContext(),
                    TelemetryOptions(endpoint = COLLECTOR_ENDPOINT, storageDirectory = null, flushInterval = 1.seconds, statsWindow = 2.seconds),
                )
                assumeTrue("telemetry pipeline started (is livekit_uniffi on jna.library.path?)", Telemetry.options != null)
                try {
                    base.evaluate()
                } finally {
                    runBlocking(Dispatchers.IO) { Telemetry.shutdown() }
                }
            }
        }
    }

    @Test
    fun sessionReachesTheCollector() = runTest {
        val marker = "telemetry e2e ${UUID.randomUUID()}"
        Telemetry.setAttribute("acme.tenant", marker)
        room.setReconnectionType(ReconnectType.FORCE_SOFT_RECONNECT)

        connect()
        val traceId = room.telemetryTraceId
        assertNotNull("each Room has a printable session trace id", traceId)
        assertEquals(32, traceId!!.length)

        // A quick reconnect on the mocks: the primary (subscriber) peer connection fails, the
        // websocket reconnects, ICE reconnects.
        disconnectPeerConnection()
        testScheduler.advanceTimeBy(1000)
        reconnectWebsocket()
        connectPeerConnection()
        // Bounded: the rtc instrument's 1 s stats loop keeps virtual time from ever going idle.
        testScheduler.advanceTimeBy(1000)

        // A published track: the server's TrackPublished answer completes the add-track request.
        val publish = launch { room.localParticipant.publishAudioTrack(createMockLocalAudioTrack()) }
        simulateMessageFromServer(TestData.LOCAL_TRACK_PUBLISHED)
        publish.join()
        assertEquals(1, room.localParticipant.audioTrackPublications.size)

        // A warn/error record emitted inside an operation ends up on that operation's span, in
        // this Room's trace — the whole path: LKLog → Telemetry → core. From a Room handler with
        // no span in flight it is filed under the Room's session; outside any Room context it is
        // the process scope.
        val op = room.beginSpan("e2e.op")
        withContext(Telemetry.currentSpan.asContextElement(op)) { LKLog.e { marker } }
        op?.end()
        val unknownSid = "TR_unknown_$marker" // the server unpublishes a track we never had: the Room handler warns
        simulateMessageFromServer(
            LivekitRtc.SignalResponse.newBuilder()
                .setTrackUnpublished(LivekitRtc.TrackUnpublishedResponse.newBuilder().setTrackSid(unknownSid))
                .build(),
        )
        LKLog.e { "$marker process" }
        room.emitTelemetryEvent("e2e.checkpoint", mapOf("e2e.marker" to marker))

        Thread.sleep(2500) // stats polls (the mocks yield empty reports, so no windows) and a flush
        room.disconnect()
        Thread.sleep(3000) // the disconnect flush, and the collector's write
        val diagnostics = Telemetry.diagnostics()
        println("telemetry: trace $traceId — $diagnostics")

        val otlp = OtlpFile(File(COLLECTOR_OUTPUT), since = startNs)
        val spans = otlp.spans.filter { it.traceId == traceId }

        // The user-initiated connect, with its steps; the reconnect is its own span.
        val connect = spans.filter { it.name == "lk.connect" }.also { assertEquals("one lk.connect per session: $spans", 1, it.size) }.single()
        // No answer_sent: the mocks report ICE connected before the launched answer coroutine runs.
        val steps = listOf("ws_open", "signal", "join_recv", "pc_created", "engine", "pc_connected", "room_connected")
        assertTrue(connect.events.toString(), connect.events.containsAll(steps))
        assertEquals("ok", connect.attributes["lk.outcome"])

        val reconnect = spans.filter { it.name == "lk.reconnect" }.also { assertEquals("one lk.reconnect: $spans", 1, it.size) }.single()
        assertEquals("subscriber_failed", reconnect.attributes["lk.reconnect.reason"])
        assertEquals("quick", reconnect.attributes["lk.reconnect.mode"])
        assertEquals("ok", reconnect.attributes["lk.outcome"])
        assertTrue(reconnect.events.toString(), "attempt 1 quick" in reconnect.events)

        val publishSpan = spans.filter { it.name == "lk.publish" }.also { assertEquals("one lk.publish: $spans", 1, it.size) }.single()
        assertEquals("ok", publishSpan.attributes["lk.outcome"])
        assertEquals("audio", publishSpan.attributes["lk.track.kind"])
        assertEquals("microphone", publishSpan.attributes["lk.track.source"])
        assertEquals(TestData.LOCAL_AUDIO_TRACK.sid, publishSpan.attributes["lk.track.sid"])

        val logs = otlp.logs
        val inSpan = logs.firstOrNull { it.body == marker }.also { assertNotNull("error record reached the collector", it) }!!
        val opSpan = otlp.spans.firstOrNull { it.name == "e2e.op" }.also { assertNotNull("custom span reached the collector", it) }!!
        assertEquals("the record points at the span it was emitted in", opSpan.spanId, inSpan.spanId)
        assertEquals("...and therefore lands in this Room's trace", traceId, inSpan.traceId)
        assertEquals("roomname", inSpan.attributes["lk.room.name"])
        assertEquals(TestData.LOCAL_PARTICIPANT.identity, inSpan.attributes["lk.participant.identity"])
        val handler = logs.firstOrNull { it.body?.endsWith(unknownSid) == true }.also { assertNotNull("Room-handler warning reached the collector", it) }!!
        assertTrue("a Room handler with no span in flight: the Room's session, no span", handler.spanId.isEmpty() && handler.traceId == traceId)
        val process = logs.firstOrNull { it.body == "$marker process" }.also { assertNotNull(it) }!!
        assertTrue("outside any Room context: the process scope", process.spanId.isEmpty() && process.traceId != traceId)
        assertTrue(logs.any { it.eventName == "custom.e2e.checkpoint" && it.attributes["e2e.marker"] == marker })
        for (event in listOf("lk.device.thermal.changed", "lk.device.memory.changed", "lk.device.network.changed", "lk.device.low_power.changed")) {
            assertTrue("$event initial value reached the collector", logs.any { it.eventName == event })
        }
        // The rule: no info-level log record leaves the device.
        assertTrue(logs.filter { it.eventName.isEmpty() }.all { it.severity >= SEVERITY_WARN })
        assertTrue("the pipeline-wide attribute reaches the Room's scope", logs.any { it.traceId == traceId && it.attributes["acme.tenant"] == marker })
        // The Room hung up itself, and said so before the disconnect flush.
        val ended = logs.filter { it.eventName == "lk.room.disconnected" && it.traceId == traceId }
        assertEquals(ended.map { it.attributes }.toString(), 1, ended.size)
        assertEquals("client_initiated", ended.single().attributes["lk.disconnect.reason"])
        // The pipeline's own health: the whole session shipped.
        assertTrue(diagnostics, "lost 0" in diagnostics)
    }

    private fun reconnectWebsocket() {
        wsFactory.listener.onOpen(wsFactory.ws, createOpenResponse(wsFactory.request))
        val softReconnectParam = wsFactory.request.url.queryParameter(SignalClient.CONNECT_QUERY_RECONNECT)?.toIntOrNull() ?: 0
        simulateMessageFromServer(if (softReconnectParam == 0) TestData.JOIN else TestData.RECONNECT)
    }

    companion object {
        const val COLLECTOR_ENDPOINT = "http://127.0.0.1:4319/v1/logs"
        const val COLLECTOR_OUTPUT = "/tmp/livekit-telemetry-otlp.jsonl"
        private const val SEVERITY_WARN = 13

        private fun collectorReachable() = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", 4319), 500) } }.isSuccess
    }
}

/** What the collector wrote: OTLP/JSON, one export request per line, from [since] (Unix nanoseconds) on. */
class OtlpFile(file: File, since: Long) {
    class Log(val eventName: String, val body: String?, val traceId: String, val spanId: String, val severity: Int, val attributes: Map<String, String>)

    /** [events] are the span's checkpoints (`ws_open`, `first_media`, `attempt 1 quick`, ...). */
    class Span(val name: String, val traceId: String, val spanId: String, val attributes: Map<String, String>, val events: List<String>)

    val logs = mutableListOf<Log>()
    val spans = mutableListOf<Span>()

    init {
        for (line in file.readLines()) {
            val request = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
            for (resource in request.array("resourceLogs")) {
                for (scope in resource.jsonObject.array("scopeLogs")) {
                    for (record in scope.jsonObject.array("logRecords")) {
                        val r = record.jsonObject
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
            }
            for (resource in request.array("resourceSpans")) {
                for (scope in resource.jsonObject.array("scopeSpans")) {
                    for (span in scope.jsonObject.array("spans")) {
                        val s = span.jsonObject
                        if (s.long("startTimeUnixNano") < since) continue
                        spans += Span(
                            name = s.string("name") ?: "",
                            traceId = s.string("traceId") ?: "",
                            spanId = s.string("spanId") ?: "",
                            attributes = attributes(s["attributes"]),
                            events = s.array("events").mapNotNull { it.jsonObject.string("name") },
                        )
                    }
                }
            }
        }
    }

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
