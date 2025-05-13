# Track Processors for LiveKit Android SDK

[![Maven Central](https://maven-badges.herokuapp.com/maven-central/io.livekit/livekit-android-track-processors/badge.svg)](https://maven-badges.herokuapp.com/maven-central/io.livekit/livekit-android-camerax)

This library provides track processors for use with the Android LiveKit SDK.

## Installation

```groovy title="build.gradle"
implementation "io.livekit:livekit-android-track-processors:<current livekit sdk release>"
```

See our [release page](https://github.com/livekit/client-sdk-android/releases) for details on the
current release version.

## Usage of prebuilt processors

This package exposes `VirtualBackgroundVideoProcessor` as a pre-prepared video processor.

```
val processor = VirtualBackgroundVideoProcessor(eglBase).apply {
    // Optionally set a background image.
    // Will blur the background of the video if none is set.
    val drawable = AppCompatResources.getDrawable(application, R.drawable.background) as BitmapDrawable
    backgroundImage = drawable.bitmap
}
```

### Register the image analyzer in the CameraProvider

`VirtualBackgroundVideoProcessor` requires the use of our CameraX provider.

```
val imageAnalysis = ImageAnalysis.Builder().build()
    .apply { setAnalyzer(Dispatchers.IO.asExecutor(), processor.imageAnalyzer) }

CameraXHelper.createCameraProvider(ProcessLifecycleOwner.get(), arrayOf(imageAnalysis)).let {
    if (it.isSupported(application)) {
        CameraCapturerUtils.registerCameraProvider(it)
    }
}
```

### Create and publish the video track

```
val videoTrack = room.localParticipant.createVideoTrack(
    options = LocalVideoTrackOptions(position = CameraPosition.FRONT),
    videoProcessor = processor,
)

videoTrack.startCapture()
room.localParticipant.publishVideoTrack(videoTrack)
```

You can find an offline example of the `VirtualBackgroundVideoProcessor` in
use [here](https://github.com/livekit/client-sdk-android/tree/main/examples/virtual-background).
