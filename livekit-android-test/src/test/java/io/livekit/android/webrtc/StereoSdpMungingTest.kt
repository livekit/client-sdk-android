/*
 * Copyright 2023-2026 LiveKit, Inc.
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

package io.livekit.android.webrtc

import android.javax.sdp.MediaDescription
import android.javax.sdp.SdpFactory
import livekit.org.webrtc.SessionDescription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StereoSdpMungingTest {

    private val sdpFactory = SdpFactory.getInstance()

    @Test
    fun ensureStereoOpusAddsStereoWhenOfferHasSpropStereo() {
        val answer = answerOf(STEREO_OFFER_DESCRIPTION)
        val offer = offerOf(STEREO_OFFER_DESCRIPTION)

        val munged = answer.ensureStereoOpus(offer)

        val audioMedia = audioMid(munged, "1")
        val fmtp = audioMedia.getFmtps().first { (_, fmtp) -> fmtp.payload == 111L }
        assertTrue(fmtp.second.config.split(";").any { it.trim() == "stereo=1" })
    }

    @Test
    fun ensureStereoOpusDoesNothingWhenOfferLacksSpropStereo() {
        val answer = answerOf(NO_STEREO_OFFER_DESCRIPTION)
        val offer = offerOf(NO_STEREO_OFFER_DESCRIPTION)

        val munged = answer.ensureStereoOpus(offer)

        assertEquals(NO_STEREO_OFFER_DESCRIPTION, munged.description)
    }

    @Test
    fun ensureStereoOpusReplacesConflictingMonoParam() {
        val answer = answerOf(ANSWER_WITH_MONO_STEREO_DESCRIPTION)
        val offer = offerOf(STEREO_OFFER_DESCRIPTION)

        val munged = answer.ensureStereoOpus(offer)

        val audioMedia = audioMid(munged, "1")
        val stereoParams = audioMedia.getFmtps()
            .first { (_, fmtp) -> fmtp.payload == 111L }
            .second.config.split(";")
            .map { it.trim() }
            .filter { it.startsWith("stereo=") }
        assertEquals(listOf("stereo=1"), stereoParams)
    }

    @Test
    fun ensureStereoOpusDoesNotDuplicateStereoParam() {
        val answer = answerOf(ANSWER_WITH_STEREO_DESCRIPTION)
        val offer = offerOf(STEREO_OFFER_DESCRIPTION)

        val munged = answer.ensureStereoOpus(offer)

        val audioMedia = audioMid(munged, "1")
        val stereoFmtps = audioMedia.getFmtps()
            .filter { (_, fmtp) ->
                fmtp.payload == 111L && fmtp.config.split(";").any { it.trim() == "stereo=1" }
            }
        assertEquals(1, stereoFmtps.size)
    }

    private fun answerOf(sdp: String): SessionDescription =
        SessionDescription(SessionDescription.Type.ANSWER, sdp)

    private fun offerOf(sdp: String): SessionDescription =
        SessionDescription(SessionDescription.Type.OFFER, sdp)

    private fun audioMid(munged: SessionDescription, mid: String): MediaDescription =
        sdpFactory.createSessionDescription(munged.description)
            .getMediaDescriptions(true)
            .filterIsInstance<MediaDescription>()
            .first { it.getAttribute("mid") == mid }

    companion object {
        // Mirrors a LiveKit subscriber offer: audio mid "1" carries opus with
        // sprop-stereo=1 (publisher published a stereo track).
        private const val STEREO_OFFER_DESCRIPTION = "v=0\r\n" +
            "o=- 8980856298632007851 1787470315 IN IP4 0.0.0.0\r\n" +
            "s=-\r\n" +
            "t=0 0\r\n" +
            "a=msid-semantic:WMS *\r\n" +
            "a=group:BUNDLE 0 1\r\n" +
            "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n" +
            "c=IN IP4 0.0.0.0\r\n" +
            "a=mid:0\r\n" +
            "a=sendrecv\r\n" +
            "a=sctp-port:5000\r\n" +
            "m=audio 9 UDP/TLS/RTP/SAVPF 111 63\r\n" +
            "c=IN IP4 0.0.0.0\r\n" +
            "a=mid:1\r\n" +
            "a=rtcp-mux\r\n" +
            "a=rtpmap:111 opus/48000/2\r\n" +
            "a=fmtp:111 minptime=10;useinbandfec=1;sprop-stereo=1\r\n" +
            "a=rtpmap:63 red/48000/2\r\n" +
            "a=fmtp:63 111/111\r\n" +
            "a=recvonly\r\n"

        // Publisher published a mono track: no sprop-stereo anywhere.
        private const val NO_STEREO_OFFER_DESCRIPTION = "v=0\r\n" +
            "o=- 8980856298632007851 1787470315 IN IP4 0.0.0.0\r\n" +
            "s=-\r\n" +
            "t=0 0\r\n" +
            "a=msid-semantic:WMS *\r\n" +
            "a=group:BUNDLE 0 1\r\n" +
            "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n" +
            "c=IN IP4 0.0.0.0\r\n" +
            "a=mid:0\r\n" +
            "a=sendrecv\r\n" +
            "a=sctp-port:5000\r\n" +
            "m=audio 9 UDP/TLS/RTP/SAVPF 111 63\r\n" +
            "c=IN IP4 0.0.0.0\r\n" +
            "a=mid:1\r\n" +
            "a=rtcp-mux\r\n" +
            "a=rtpmap:111 opus/48000/2\r\n" +
            "a=fmtp:111 minptime=10;useinbandfec=1\r\n" +
            "a=rtpmap:63 red/48000/2\r\n" +
            "a=fmtp:63 111/111\r\n" +
            "a=recvonly\r\n"

        // The answer negotiated stereo=0: munging must replace it with stereo=1.
        private const val ANSWER_WITH_MONO_STEREO_DESCRIPTION = "v=0\r\n" +
            "o=- 3119613797835240840 4 IN IP4 127.0.0.1\r\n" +
            "s=-\r\n" +
            "t=0 0\r\n" +
            "a=group:BUNDLE 0 1\r\n" +
            "a=msid-semantic: WMS\r\n" +
            "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n" +
            "c=IN IP4 0.0.0.0\r\n" +
            "a=mid:0\r\n" +
            "a=sctp-port:5000\r\n" +
            "m=audio 9 UDP/TLS/RTP/SAVPF 111 63\r\n" +
            "c=IN IP4 0.0.0.0\r\n" +
            "a=mid:1\r\n" +
            "a=rtcp-mux\r\n" +
            "a=rtpmap:111 opus/48000/2\r\n" +
            "a=fmtp:111 minptime=10;useinbandfec=1;stereo=0\r\n" +
            "a=rtpmap:63 red/48000/2\r\n" +
            "a=fmtp:63 111/111\r\n" +
            "a=recvonly\r\n"

        // The answer already negotiated stereo=1: munging must not duplicate it.
        private const val ANSWER_WITH_STEREO_DESCRIPTION = "v=0\r\n" +
            "o=- 3119613797835240840 4 IN IP4 127.0.0.1\r\n" +
            "s=-\r\n" +
            "t=0 0\r\n" +
            "a=group:BUNDLE 0 1\r\n" +
            "a=msid-semantic: WMS\r\n" +
            "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n" +
            "c=IN IP4 0.0.0.0\r\n" +
            "a=mid:0\r\n" +
            "a=sctp-port:5000\r\n" +
            "m=audio 9 UDP/TLS/RTP/SAVPF 111 63\r\n" +
            "c=IN IP4 0.0.0.0\r\n" +
            "a=mid:1\r\n" +
            "a=rtcp-mux\r\n" +
            "a=rtpmap:111 opus/48000/2\r\n" +
            "a=fmtp:111 minptime=10;useinbandfec=1;stereo=1\r\n" +
            "a=rtpmap:63 red/48000/2\r\n" +
            "a=fmtp:63 111/111\r\n" +
            "a=recvonly\r\n"
    }
}
