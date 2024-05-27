/*
 * Copyright 2023-2024 LiveKit, Inc.
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
import android.javax.sdp.SessionDescription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class JainSdpUtilsTest {

    private val sdpFactory = SdpFactory.getInstance()
    private fun createSessionDescription(): SessionDescription {
        return sdpFactory.createSessionDescription(DESCRIPTION)
    }

    @Test
    fun getRtpAttributes() {
        val sdp = createSessionDescription()
        val mediaDescriptions = sdp.getMediaDescriptions(true)
            .filterIsInstance<MediaDescription>()
        val mediaDesc = mediaDescriptions[1]
        val rtps = mediaDesc.getRtps()
        assertEquals(13, rtps.size)

        val (_, vp8Rtp) = rtps[0]

        assertEquals(96, vp8Rtp.payload)
        assertEquals("VP8", vp8Rtp.codec)
        assertEquals(90000L, vp8Rtp.rate)
        assertNull(vp8Rtp.encoding)
    }

    @Test
    fun getExtmapAttributes() {
        val sdp = createSessionDescription()
        val mediaDescriptions = sdp.getMediaDescriptions(true)
            .filterIsInstance<MediaDescription>()
        val mediaDesc = mediaDescriptions[1]
        val exts = mediaDesc.getExts()

        assertEquals(12, exts.size)

        val (_, ext) = exts[0]
        assertEquals(1, ext.value)
        assertNull(ext.direction)
        assertNull(ext.encryptUri)
        assertEquals("urn:ietf:params:rtp-hdrext:toffset", ext.uri)
        assertNull(ext.config)
    }

    @Test
    fun getMsid() {
        val sdp = createSessionDescription()
        val mediaDescriptions = sdp.getMediaDescriptions(true)
            .filterIsInstance<MediaDescription>()
        val mediaDesc = mediaDescriptions[1]

        val msid = mediaDesc.getMsid()
        assertNotNull(msid)
        assertEquals("PA_Qwqk4y9fcD3G 42dd9185-4ea2-4bf1-b964-1dc0eb739c6c", msid!!.value)
    }

    @Test
    fun getFmtps() {
        val sdp = createSessionDescription()
        val mediaDescriptions = sdp.getMediaDescriptions(true)
            .filterIsInstance<MediaDescription>()
        val mediaDesc = mediaDescriptions[1]

        val fmtps = mediaDesc.getFmtps()
            .filter { (_, fmtp) -> fmtp.payload == 97L }
        assertEquals(1, fmtps.size)

        val (_, fmtp) = fmtps[0]
        assertEquals("apt=96", fmtp.config)
    }

    companion object {
        const val DESCRIPTION = "v=0\n" +
            "o=- 3682890773448528616 3 IN IP4 127.0.0.1\n" +
            "s=-\n" +
            "t=0 0\n" +
            "a=group:BUNDLE 0 1\n" +
            "a=extmap-allow-mixed\n" +
            "a=msid-semantic: WMS PA_Qwqk4y9fcD3G\n" +
            "m=application 24436 UDP/DTLS/SCTP webrtc-datachannel\n" +
            "c=IN IP4 45.76.222.83\n" +
            "a=candidate:1660983843 1 udp 2122194687 192.168.0.22 37324 typ host generation 0 network-id 5 network-cost 10\n" +
            "a=candidate:901011812 1 udp 2122262783 2400:4050:28c2:200:9ce6:9cff:fe22:a74d 48779 typ host generation 0 network-id 6 network-cost 10\n" +
            "a=candidate:3061937641 1 udp 1685987071 123.222.220.24 37324 typ srflx raddr 192.168.0.22 rport 37324 generation 0 network-id 5 network-cost 10\n" +
            "a=candidate:689824452 1 tcp 1518083839 10.244.232.137 9 typ host tcptype active generation 0 network-id 3 network-cost 900\n" +
            "a=candidate:2149072150 1 tcp 1518151935 2407:5300:1029:7a1f:96b6:6f1e:ec66:64d8 9 typ host tcptype active generation 0 network-id 4 network-cost 900\n" +
            "a=candidate:3396018872 1 udp 41820671 45.76.222.83 24436 typ relay raddr 123.222.220.24 rport 37324 generation 0 network-id 5 network-cost 10\n" +
            "a=ice-ufrag:+2SN\n" +
            "a=ice-pwd:cdmp3JptAqdOA9VRHrNsdKE9\n" +
            "a=ice-options:trickle renomination\n" +
            "a=fingerprint:sha-256 44:C7:59:DD:54:91:AC:EA:93:07:8E:4F:78:C5:A6:9B:FB:C3:16:2B:95:1C:9E:DB:3B:AE:8A:E5:76:37:6F:A2\n" +
            "a=setup:actpass\n" +
            "a=mid:0\n" +
            "a=sctp-port:5000\n" +
            "a=max-message-size:262144\n" +
            "m=video 9 UDP/TLS/RTP/SAVPF 96 97 127 103 104 105 39 40 98 99 106 107 108\n" +
            "c=IN IP4 0.0.0.0\n" +
            "a=rtcp:9 IN IP4 0.0.0.0\n" +
            "a=ice-ufrag:+2SN\n" +
            "a=ice-pwd:cdmp3JptAqdOA9VRHrNsdKE9\n" +
            "a=ice-options:trickle renomination\n" +
            "a=fingerprint:sha-256 44:C7:59:DD:54:91:AC:EA:93:07:8E:4F:78:C5:A6:9B:FB:C3:16:2B:95:1C:9E:DB:3B:AE:8A:E5:76:37:6F:A2\n" +
            "a=setup:actpass\n" +
            "a=mid:1\n" +
            "a=extmap:1 urn:ietf:params:rtp-hdrext:toffset\n" +
            "a=extmap:2 http://www.webrtc.org/experiments/rtp-hdrext/abs-send-time\n" +
            "a=extmap:3 urn:3gpp:video-orientation\n" +
            "a=extmap:4 http://www.ietf.org/id/draft-holmer-rmcat-transport-wide-cc-extensions-01\n" +
            "a=extmap:5 http://www.webrtc.org/experiments/rtp-hdrext/playout-delay\n" +
            "a=extmap:6 http://www.webrtc.org/experiments/rtp-hdrext/video-content-type\n" +
            "a=extmap:7 http://www.webrtc.org/experiments/rtp-hdrext/video-timing\n" +
            "a=extmap:8 http://www.webrtc.org/experiments/rtp-hdrext/color-space\n" +
            "a=extmap:9 urn:ietf:params:rtp-hdrext:sdes:mid\n" +
            "a=extmap:10 urn:ietf:params:rtp-hdrext:sdes:rtp-stream-id\n" +
            "a=extmap:11 urn:ietf:params:rtp-hdrext:sdes:repaired-rtp-stream-id\n" +
            "a=extmap:12 https://aomediacodec.github.io/av1-rtp-spec/#dependency-descriptor-rtp-header-extension\n" +
            "a=sendonly\n" +
            "a=msid:PA_Qwqk4y9fcD3G 42dd9185-4ea2-4bf1-b964-1dc0eb739c6c\n" +
            "a=rtcp-mux\n" +
            "a=rtcp-rsize\n" +
            "a=rtpmap:96 VP8/90000\n" +
            "a=rtcp-fb:96 goog-remb\n" +
            "a=rtcp-fb:96 transport-cc\n" +
            "a=rtcp-fb:96 ccm fir\n" +
            "a=rtcp-fb:96 nack\n" +
            "a=rtcp-fb:96 nack pli\n" +
            "a=rtpmap:97 rtx/90000\n" +
            "a=fmtp:97 apt=96\n" +
            "a=rtpmap:127 H264/90000\n" +
            "a=rtcp-fb:127 goog-remb\n" +
            "a=rtcp-fb:127 transport-cc\n" +
            "a=rtcp-fb:127 ccm fir\n" +
            "a=rtcp-fb:127 nack\n" +
            "a=rtcp-fb:127 nack pli\n" +
            "a=fmtp:127 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f\n" +
            "a=rtpmap:103 rtx/90000\n" +
            "a=fmtp:103 apt=127\n" +
            "a=rtpmap:104 H265/90000\n" +
            "a=rtcp-fb:104 goog-remb\n" +
            "a=rtcp-fb:104 transport-cc\n" +
            "a=rtcp-fb:104 ccm fir\n" +
            "a=rtcp-fb:104 nack\n" +
            "a=rtcp-fb:104 nack pli\n" +
            "a=rtpmap:105 rtx/90000\n" +
            "a=fmtp:105 apt=104\n" +
            "a=rtpmap:39 AV1/90000\n" +
            "a=rtcp-fb:39 goog-remb\n" +
            "a=rtcp-fb:39 transport-cc\n" +
            "a=rtcp-fb:39 ccm fir\n" +
            "a=rtcp-fb:39 nack\n" +
            "a=rtcp-fb:39 nack pli\n" +
            "a=rtpmap:40 rtx/90000\n" +
            "a=fmtp:40 apt=39\n" +
            "a=rtpmap:98 VP9/90000\n" +
            "a=rtcp-fb:98 goog-remb\n" +
            "a=rtcp-fb:98 transport-cc\n" +
            "a=rtcp-fb:98 ccm fir\n" +
            "a=rtcp-fb:98 nack\n" +
            "a=rtcp-fb:98 nack pli\n" +
            "a=fmtp:98 profile-id=0\n" +
            "a=rtpmap:99 rtx/90000\n" +
            "a=fmtp:99 apt=98\n" +
            "a=rtpmap:106 red/90000\n" +
            "a=rtpmap:107 rtx/90000\n" +
            "a=fmtp:107 apt=106\n" +
            "a=rtpmap:108 ulpfec/90000\n" +
            "a=rid:h send\n" +
            "a=rid:q send\n" +
            "a=simulcast:send h;q"
    }
}
