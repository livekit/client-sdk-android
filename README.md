<!--BEGIN_BANNER_IMAGE--><!--END_BANNER_IMAGE-->

# Android Kotlin SDK for LiveKit

<!--BEGIN_DESCRIPTION-->
Official Android Client SDK for [LiveKit](https://github.com/livekit/livekit-server). Easily add
video & audio capabilities to your Android apps.
<!--END_DESCRIPTION-->

Table of Contents
=================

   * [Docs](#docs)
   * [Installation](#installation)
   * [Usage](#usage)
      * [Permissions](#permissions)
      * [Publishing camera and microphone](#publishing-camera-and-microphone)
      * [Sharing screen](#sharing-screen)
      * [Rendering subscribed tracks](#rendering-subscribed-tracks)
      * [@FlowObservable](#flowobservable)
   * [Sample App](#sample-app)
   * [Dev Environment](#dev-environment)
      * [Optional (Dev convenience)](#optional-dev-convenience)
      
## Docs

Docs and guides at [https://docs.livekit.io](https://docs.livekit.io).

API reference can be found
at [https://docs.livekit.io/client-sdk-android/index.html](https://docs.livekit.io/client-sdk-android/index.html)
.

## Installation

LiveKit for Android is available as a Maven package.

```groovy title="build.gradle"
...
dependencies {
  implementation "io.livekit:livekit-android:1.1.9"
  // Snapshots of the latest development version are available at:
  // implementation "io.livekit:livekit-android:1.1.10-SNAPSHOT"
}
```

You'll also need jitpack as one of your repositories.

```groovy
subprojects {
    repositories {
        google()
        mavenCentral()
        // ...
        maven { url 'https://jitpack.io' }
        
        // For SNAPSHOT access
        // maven { url 'https://s01.oss.sonatype.org/content/repositories/snapshots/' }
    }
}
```

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

LiveKit uses `SurfaceViewRenderer` to render video tracks. A `TextureView` implementation is also
provided through `TextureViewRenderer`. Subscribed audio tracks are automatically played.

```kt
class MainActivity : AppCompatActivity() {

    lateinit var room: Room

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        // Create Room object.
        room = LiveKit.create(applicationContext)

        // Setup the video renderer
        room.initVideoRenderer(findViewById<SurfaceViewRenderer>(R.id.renderer))

        connectToRoom()
    }

    private fun connectToRoom() {

        val url = "wss://your_host"
        val token = "your_token"

        lifecycleScope.launch {

            // Setup event handling.
            launch {
                room.events.collect { event ->
                    when (event) {
                        is RoomEvent.TrackSubscribed -> onTrackSubscribed(event)
                        else -> {}
                    }
                }
            }

            // Connect to server.
            room.connect(
                url,
                token,
            )

            // Turn on audio/video recording.
            val localParticipant = room.localParticipant
            localParticipant.setMicrophoneEnabled(true)
            localParticipant.setCameraEnabled(true)
        }
    }

    private fun onTrackSubscribed(event: RoomEvent.TrackSubscribed) {
        val track = event.track
        if (track is VideoTrack) {
            attachVideo(track)
        }
    }

    private fun attachVideo(videoTrack: VideoTrack) {
        videoTrack.addRenderer(findViewById<SurfaceViewRenderer>(R.id.renderer))
        findViewById<View>(R.id.progress).visibility = View.GONE
    }
}
```

See
the [basic sample app](https://github.com/livekit/client-sdk-android/blob/main/sample-app-basic/src/main/java/io/livekit/android/sample/basic/MainActivity.kt)
for the full implementation.

### `@FlowObservable`

Properties marked with `@FlowObservable` can be accessed as a Kotlin Flow to observe changes
directly:

```kt
coroutineScope.launch {
    room::activeSpeakers.flow.collectLatest { speakersList ->
        /*...*/
    }
}
```

## Sample App

**Note**: If you wish to run the sample apps directly from this repo, please consult the [Dev Environment instructions](#dev-environment).

We have a basic quickstart sample
app [here](https://github.com/livekit/client-sdk-android/blob/main/sample-app-basic), showing how to
connect to a room, publish your device's audio/video, and display the video of one remote participant.

There are two more full featured video conferencing sample apps:

* [Compose app](https://github.com/livekit/client-sdk-android/tree/main/sample-app-compose/src/main/java/io/livekit/android/composesample)
* [Standard app](https://github.com/livekit/client-sdk-android/tree/main/sample-app/src/main/java/io/livekit/android/sample)

They both use
the [`CallViewModel`](https://github.com/livekit/client-sdk-android/blob/main/sample-app-common/src/main/java/io/livekit/android/sample/CallViewModel.kt)
, which handles the `Room` connection and exposes the data needed for a basic video conferencing
app.

The respective `ParticipantItem` class in each app is responsible for the displaying of each
participant's UI.

* [Compose `ParticipantItem`](https://github.com/livekit/client-sdk-android/blob/main/sample-app-compose/src/main/java/io/livekit/android/composesample/ParticipantItem.kt)
* [Standard `ParticipantItem`](https://github.com/livekit/client-sdk-android/blob/main/sample-app/src/main/java/io/livekit/android/sample/ParticipantItem.kt)

## Dev Environment

To develop the Android SDK or running the sample app directly from this repo, you'll need:

- Clone the repo to your computer
- Ensure the protocol submodule repo is initialized and updated

```
git clone https://github.com/livekit/client-sdk-android.git
cd client-sdk-android
git submodule update --init
```

----

For those developing on Apple M1 Macs, please add below to $HOME/.gradle/gradle.properties

```
protoc_platform=osx-x86_64
```

### Optional (Dev convenience)

1. Download webrtc sources from https://webrtc.googlesource.com/src
2. Add sources to Android Studio by pointing at the `webrtc/sdk/android` folder.

<!--BEGIN_REPO_NAV--><!--END_REPO_NAV-->
