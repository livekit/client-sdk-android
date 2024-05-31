# CameraX support for LiveKit Android SDK

This library provides an CameraX integration for use with the Android LiveKit SDK. This provides access to more camera functionality such as custom zoom and torch control.

## Installation

```groovy title="build.gradle"
...
dependencies {
  implementation "io.livekit:livekit-android-camerax:<current livekit sdk release>"
}
```

See our [release page](https://github.com/livekit/client-sdk-android/releases) for details on the current release version.

## Usage

### Register the CameraProvider

```
CameraXHelper.createCameraProvider(lifecycleOwner).let {
    if (it.isSupported(application)) {
        CameraCapturerUtils.registerCameraProvider(it)

        // Save cameraProvider for unregistration later.
        cameraProvider = it
    }
}
```

Your activity can act as your `LifecycleOwner` for the camera provider. If you wish to use the camera beyond the lifecycle of a single activity, consider using 
[viewmodel-lifecycle](https://github.com/skydoves/viewmodel-lifecycle) for use within a view model (useful if your activity wants to handle rotation or other configuration changes),
or `LifecycleService` from `androidx.lifecycle:lifecycle-service` to use in a service for backgrounded camera usage.

Once registered, LiveKit will default to using CameraX when creating a camera video track.

### Accessing the camera controls

```
fun zoom(factor: Float) {
    val camera = localVideoTrack.capturer.getCameraX()?.value ?: return
    val zoomState = camera.cameraInfo.zoomState.value ?: return
    val currentZoom = zoomState.zoomRatio
    val newZoom = (currentZoom * factor).coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)

    if (newZoom != currentZoom) {
        camera.cameraControl.setZoomRatio(newZoom)
    }
}
```

We provide a convenience `ScaleZoomHelper` class that can handle pinch-to-zoom functionality as well.
