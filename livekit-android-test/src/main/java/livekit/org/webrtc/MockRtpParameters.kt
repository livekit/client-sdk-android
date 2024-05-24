/*
 * Copyright 2024 LiveKit, Inc.
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

package livekit.org.webrtc

class MockRtpParameters(
    transactionId: String?,
    degradationPreference: DegradationPreference?,
    rtcp: Rtcp?,
    headerExtensions: MutableList<HeaderExtension>?,
    encodings: MutableList<Encoding>?,
    codecs: MutableList<Codec>?,
) : RtpParameters(transactionId, degradationPreference, rtcp, headerExtensions, encodings, codecs) {
    class MockRtcp(cname: String?, reducedSize: Boolean) : Rtcp(cname, reducedSize)
    class MockHeaderExtension(uri: String?, id: Int, encrypted: Boolean) : HeaderExtension(uri, id, encrypted)
    class MockCodec(payloadType: Int, name: String?, kind: MediaStreamTrack.MediaType?, clockRate: Int?, numChannels: Int?, parameters: MutableMap<String, String>?) :
        Codec(payloadType, name, kind, clockRate, numChannels, parameters)
}
