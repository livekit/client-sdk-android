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
