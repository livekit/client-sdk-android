# sample-app-record-local

An example showing how to save the local device's audio and video tracks.

While connected to a Room, this app will save a video from your microphone and camera. Audio samples
and video frames are passed into a `VideoFileRenderer` object, where they are then encoded using
`android.media.MediaCodec`and saved using a `android.media.MediaMuxer` into a video file.

Videos are saved to the app's external files directory (
normally `/sdcard/Android/data/io.livekit.android.sample.record/files/Movies`).
