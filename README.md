# Android Kotlin SDK for LiveKit

Official Android Client SDK for [LiveKit](https://github.com/livekit/livekit-server). Easily add video & audio capabilities to your Android apps.

## Docs

Docs and guides at [https://docs.livekit.io](https://docs.livekit.io)

## Installation

LiveKit for Android is available as a Maven package.

```groovy title="build.gradle"
...
dependencies {
  implementation "io.livekit:livekit-android:<version>"
}
```

## Usage

LiveKit uses WebRTC-provided `org.webrtc.SurfaceViewRenderer` to render video tracks. Subscribed audio tracks are automatically played.

```kt
class MainActivity : AppCompatActivity(), RoomListener {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ...
        val url = "wss://your_host";
        val token = "your_token"

        launch {
            val room = LiveKit.connect(
                applicationContext,
                url,
                token,
                ConnectOptions(),
                this
            )
            val localParticipant = room.localParticipant
            val audioTrack = localParticipant.createAudioTrack()
            localParticipant.publishAudioTrack(audioTrack)
            val videoTrack = localParticipant.createVideoTrack()
            localParticipant.publishVideoTrack(videoTrack)
            videoTrack.startCapture()

            attachVideo(videoTrack, localParticipant)
        }
    }

    override fun onTrackSubscribed(
        track: Track,
        publication: RemoteTrackPublication,
        participant: RemoteParticipant,
        room: Room
    ) {
        if (track is VideoTrack) {
            attachVideo(track, participant)
        }
    }

    private fun attachVideo(videoTrack: VideoTrack, participant: Participant) {
        // viewBinding.renderer is a `org.webrtc.SurfaceViewRenderer` in your
        // layout
        videoTrack.addRenderer(viewBinding.renderer)
    }
}
```

## Dev Environment

To develop the Android SDK itself, you'll need:

- Ensure the protocol submodule repo is initialized and updated with `git submodule --init`
- Install [Android Studio Arctic Fox 2020.3.1+](https://developer.android.com/studio)

### Optional (Dev convenience)

1. Download webrtc sources from https://webrtc.googlesource.com/src
2. Add sources to Android Studio by pointing at the `webrtc/sdk/android` folder.
