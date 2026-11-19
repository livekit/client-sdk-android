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

import android.javax.sdp.MediaDescription
import android.javax.sdp.SdpFactory
import org.junit.Assert.assertEquals
import org.junit.Test

class OpusNackSdpTest {
    @Test
    fun addsPlainNackToEveryOpusPayloadAndPreservesOtherSections() {
        for (direction in listOf("sendonly", "recvonly", "sendrecv", "inactive")) {
            for (eol in listOf("\n", "\r\n")) {
                val offer = listOf(
                    "v=0",
                    "o=- 1 1 IN IP4 127.0.0.1",
                    "s=-",
                    "t=0 0",
                    "m=audio 9 UDP/TLS/RTP/SAVPF 109 112",
                    "a=$direction",
                    "a=rtpmap:109 OPUS/48000/2",
                    "a=rtpmap:112 opus/48000/2",
                    "a=rtcp-fb:109 nack pli",
                    "m=video 9 UDP/TLS/RTP/SAVPF 109",
                    "a=rtpmap:109 VP8/90000",
                    "m=audio 9 UDP/TLS/RTP/SAVPF 109",
                    "a=rtpmap:109 PCMU/8000",
                    "",
                ).joinToString(eol)
                val document = SdpFactory.getInstance().createSessionDescription(offer)
                val sections = document.getMediaDescriptions(true).filterIsInstance<MediaDescription>()
                val original = sections.map { it.toString() }

                sections.forEach(::ensureOpusNack)

                val audio = sections.first().toString().lineSequence().toList()
                assertEquals(1, audio.count { it == "a=rtcp-fb:109 nack" })
                assertEquals(1, audio.count { it == "a=rtcp-fb:112 nack" })
                assertEquals(1, audio.count { it == "a=rtcp-fb:109 nack pli" })
                assertEquals(original.drop(1), sections.drop(1).map { it.toString() })
                val once = document.toString()
                sections.forEach(::ensureOpusNack)
                assertEquals(once, document.toString())
            }
        }
    }

    @Test
    fun preservesExistingPlainAndWildcardNackIncludingWhitespace() {
        for (feedback in listOf("109 nack", "* nack", "109  nack", "109\tnack")) {
            val section = audioSection(feedback)
            val original = section.toString()

            ensureOpusNack(section)

            assertEquals(original, section.toString())
        }
    }

    @Test
    fun addsNackOnlyToPayloadsNotAlreadyCovered() {
        val section = SdpFactory.getInstance().createSessionDescription(
            "m=audio 9 UDP/TLS/RTP/SAVPF 109 112\r\n" +
                "a=rtpmap:109 opus/48000/2\r\n" +
                "a=rtpmap:112 opus/48000/2\r\n" +
                "a=rtcp-fb:112 nack\r\n",
        ).getMediaDescriptions(true).filterIsInstance<MediaDescription>().single()

        ensureOpusNack(section)

        val lines = section.toString().lineSequence().toList()
        assertEquals(1, lines.count { it == "a=rtcp-fb:109 nack" })
        assertEquals(1, lines.count { it == "a=rtcp-fb:112 nack" })
    }

    private fun audioSection(feedback: String): MediaDescription {
        return SdpFactory.getInstance().createSessionDescription(
            "m=audio 9 UDP/TLS/RTP/SAVPF 109\r\n" +
                "a=rtpmap:109 opus/48000/2\r\n" +
                "a=rtcp-fb:$feedback\r\n",
        ).getMediaDescriptions(true).filterIsInstance<MediaDescription>().single()
    }
}
