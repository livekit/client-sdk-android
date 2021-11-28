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

## Sample App

There are two sample apps with similar functionality:

* [Compose app](https://github.com/livekit/client-sdk-android/tree/master/sample-app-compose/src/main/java/io/livekit/android/composesample) 
* [Standard app](https://github.com/livekit/client-sdk-android/tree/master/sample-app)

## Usage

### Permissions

LiveKit relies on the `RECORD_AUDIO` and `CAMERA` permissions to use the microphone and camera.
These permission must be requested at runtime. Reference the [sample app](https://github.com/livekit/client-sdk-android/blob/4e76e36e0d9f895c718bd41809ab5ff6c57aabd4/sample-app-compose/src/main/java/io/livekit/android/composesample/MainActivity.kt#L134) for an example.

### Publishing camera and microphone

```kt
room.localParticipant.setCameraEnabled(true)
room.localParticipant.setMicrophoneEnabled(true)
```

### Sharing screen

```kt
// create an intent launcher for screen capture
// this *must* be registered prior to onCreate(), ideally as an instance val
val screenCaptureIntentLauncher = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult()
) { result ->
    val resultCode = result.resultCode
    val data = result.data
    if (resultCode != Activity.RESULT_OK || data == null) {
        return@registerForActivityResult
    }
    lifecycleScope.launch {
        room.localParticipant.setScreenShareEnabled(true, data)
    }
}

// when it's time to enable the screen share, perform the following
val mediaProjectionManager =
    getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
screenCaptureIntentLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
```

### Rendering subscribed tracks

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
            localParticipant.setMicrophoneEnabled(true)
            localParticipant.setCameraEnabled(true)

            attachVideo(videoTrack)
        }
    }

    override fun onTrackSubscribed(
        track: Track,
        publication: RemoteTrackPublication,
        participant: RemoteParticipant,
        room: Room
    ) {
        if (track is VideoTrack) {
            attachVideo(track)
        }
    }

    private fun attachVideo(videoTrack: VideoTrack) {
        // viewBinding.renderer is a `org.webrtc.SurfaceViewRenderer` in your
        // layout
        videoTrack.addRenderer(viewBinding.renderer)
    }
}
```

## Dev Environment

To develop the Android SDK or running the sample app, you'll need:

- Ensure the protocol submodule repo is initialized and updated with `git submodule update --init`
- Install [Android Studio Arctic Fox 2020.3.1+](https://developer.android.com/studio)

For those developing on Apple M1 Macs, please add below to $HOME/.gradle/gradle.properties

```
protoc_platform=osx-x86_64
```

### Optional (Dev convenience)

1. Download webrtc sources from https://webrtc.googlesource.com/src
2. Add sources to Android Studio by pointing at the `webrtc/sdk/android` folder.
