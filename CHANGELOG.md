# client-sdk-android

## 2.18.3

### Patch Changes

- Move audio handling to background thread to avoid UI freezes. - [#715](https://github.com/livekit/client-sdk-android/pull/715) ([@davidliu](https://github.com/davidliu))

## 2.18.2

### Patch Changes

- Properly return Result on ByteStreamSender convenience methods - [#709](https://github.com/livekit/client-sdk-android/pull/709) ([@davidliu](https://github.com/davidliu))

## 2.18.1

### Patch Changes

- Fix not being able to publish immediately after connecting - [#706](https://github.com/livekit/client-sdk-android/pull/706) ([@davidliu](https://github.com/davidliu))

## 2.18.0

### Minor Changes

- Refactor some internal data message sending methods to use Result instead of throwing Exceptions to - [#703](https://github.com/livekit/client-sdk-android/pull/703) ([@davidliu](https://github.com/davidliu))
  fix crashes.

## 2.17.1

### Patch Changes

- Update CameraX dependency to 1.4.2 - [#694](https://github.com/livekit/client-sdk-android/pull/694) ([@davidliu](https://github.com/davidliu))

- Avoid a crash on reconnection when a track is disposed - [#691](https://github.com/livekit/client-sdk-android/pull/691) ([@jeankruger](https://github.com/jeankruger))

## 2.17.0

### Minor Changes

- Change isMicrophoneEnabled, isCameraEnabled, isScreenshareEnabled to FlowObservable variables - [#685](https://github.com/livekit/client-sdk-android/pull/685) ([@davidliu](https://github.com/davidliu))

### Patch Changes

- Fix switchCamera not working if the camera id is physical id - [#676](https://github.com/livekit/client-sdk-android/pull/676) ([@KasemJaffer](https://github.com/KasemJaffer))

- Fix sending pre-connect audio data when byte buffer has backing array - [#678](https://github.com/livekit/client-sdk-android/pull/678) ([@davidliu](https://github.com/davidliu))

- Specify default values for StreamTextOptions and streamText - [#688](https://github.com/livekit/client-sdk-android/pull/688) ([@davidliu](https://github.com/davidliu))

## 2.16.0

### Minor Changes

- Unorder the lossy data channel - [#665](https://github.com/livekit/client-sdk-android/pull/665) ([@bcherry](https://github.com/bcherry))

- Add pre-connect audio for use with agents - [#666](https://github.com/livekit/client-sdk-android/pull/666) ([@davidliu](https://github.com/davidliu))

  See Room.withPreconnectAudio for details.

- CameraX: support for selecting cameras by their physical id - [#668](https://github.com/livekit/client-sdk-android/pull/668) ([@KasemJaffer](https://github.com/KasemJaffer))

- Add Participant.State and related events - [#666](https://github.com/livekit/client-sdk-android/pull/666) ([@davidliu](https://github.com/davidliu))

### Patch Changes

- Fix NPE when streaming text - [#670](https://github.com/livekit/client-sdk-android/pull/670) ([@davidliu](https://github.com/davidliu))

- Add rpc handler methods to Room class for convenience. - [#663](https://github.com/livekit/client-sdk-android/pull/663) ([@davidliu](https://github.com/davidliu))

- Fix outgoing datastreams incorrectly padding data - [#666](https://github.com/livekit/client-sdk-android/pull/666) ([@davidliu](https://github.com/davidliu))

## 2.15.0

### Minor Changes

- Add VirtualBackgroundVideoProcessor and track-processors package - [#660](https://github.com/livekit/client-sdk-android/pull/660) ([@davidliu](https://github.com/davidliu))

## 2.14.3

### Patch Changes

- Fixes disconnect issue - [#656](https://github.com/livekit/client-sdk-android/pull/656) ([@jeankruger](https://github.com/jeankruger))

## 2.14.2

### Patch Changes

- Fix CameraXSession not setting the target capture format correctly - [#652](https://github.com/livekit/client-sdk-android/pull/652) ([@davidliu](https://github.com/davidliu))

- Improved handling of track publication failures by introducing a new TrackPublicationFailed event and fixing a broken state issue where the track remained active but inaccessible, causing the microphone or camera to stay on without a published track and leading to unreliable republishing. - [#637](https://github.com/livekit/client-sdk-android/pull/637) ([@jeankruger](https://github.com/jeankruger))

## 2.14.1

### Patch Changes

- Fix ConcurrentModificationException in IncomingDataStreamManager - [#642](https://github.com/livekit/client-sdk-android/pull/642) ([@davidliu](https://github.com/davidliu))

- Dedupe supported codecs to provide valid SDP - [#643](https://github.com/livekit/client-sdk-android/pull/643) ([@davidliu](https://github.com/davidliu))

## 2.14.0

### Minor Changes

- Implement data streams feature - [#625](https://github.com/livekit/client-sdk-android/pull/625) ([@davidliu](https://github.com/davidliu))

### Patch Changes

- Unpublish the screen sharing track on stop and introduce ScreenCaptureParams to be able to define the notification and set a callback for onStop - [#626](https://github.com/livekit/client-sdk-android/pull/626) ([@jeankruger](https://github.com/jeankruger))

## 2.13.0

### Minor Changes

- Prewarm audio to speed up mic publishing - [#623](https://github.com/livekit/client-sdk-android/pull/623) ([@davidliu](https://github.com/davidliu))

- Fast track publication support - [#612](https://github.com/livekit/client-sdk-android/pull/612) ([@davidliu](https://github.com/davidliu))

### Patch Changes

- Fix publish deadlock when no response from server - [#618](https://github.com/livekit/client-sdk-android/pull/618) ([@davidliu](https://github.com/davidliu))

- Add SCREEN_SHARE_AUDIO as a Track.Source.Type - [#610](https://github.com/livekit/client-sdk-android/pull/610) ([@davidliu](https://github.com/davidliu))

- Surface canPublishSources, canUpdateMetadata, and canSubscribeMetrics on ParticipantPermission - [#610](https://github.com/livekit/client-sdk-android/pull/610) ([@davidliu](https://github.com/davidliu))

- Fast fail attempts to publish without permissions - [#618](https://github.com/livekit/client-sdk-android/pull/618) ([@davidliu](https://github.com/davidliu))

## 2.12.3

### Patch Changes

- Fixes deadlock on publish track - [#604](https://github.com/livekit/client-sdk-android/pull/604) ([@jeankruger](https://github.com/jeankruger))

## 2.12.2

### Patch Changes

- Add version number to rpc requests - [#605](https://github.com/livekit/client-sdk-android/pull/605) ([@davidliu](https://github.com/davidliu))

## 2.12.1

### Patch Changes

- Fix documented default of preferredDeviceList in AudioSwitchHandler - [#584](https://github.com/livekit/client-sdk-android/pull/584) ([@davidliu](https://github.com/davidliu))

- Allow access to participant field in ParticipantAttributesChanged event - [#591](https://github.com/livekit/client-sdk-android/pull/591) ([@binkos](https://github.com/binkos))

## 2.12.0

### Minor Changes

- Default prioritizing speaker over earpiece - [#579](https://github.com/livekit/client-sdk-android/pull/579) ([@davidliu](https://github.com/davidliu))

- Implement RPC - [#578](https://github.com/livekit/client-sdk-android/pull/578) ([@davidliu](https://github.com/davidliu))

- Explicitly expose AudioSwitchHandler from Room for easier audio handling - [#579](https://github.com/livekit/client-sdk-android/pull/579) ([@davidliu](https://github.com/davidliu))

### Patch Changes

- Add publishDTMF method for Sending DTMF signals to SIP Participant - [#576](https://github.com/livekit/client-sdk-android/pull/576) ([@dipak140](https://github.com/dipak140))

## 2.11.1

### Patch Changes

- Fix maxFps not applying for very low framerates - [#573](https://github.com/livekit/client-sdk-android/pull/573) ([@davidliu](https://github.com/davidliu))

## 2.11.0

### Minor Changes

- Add use cases to CameraX createCameraProvider - [#536](https://github.com/livekit/client-sdk-android/pull/536) ([@KasemJaffer](https://github.com/KasemJaffer))

- Detect rotation for screenshare tracks - [#552](https://github.com/livekit/client-sdk-android/pull/552) ([@davidliu](https://github.com/davidliu))

- Default to scaling and cropping camera output to fit desired dimensions - [#558](https://github.com/livekit/client-sdk-android/pull/558) ([@davidliu](https://github.com/davidliu))

  - This behavior may be turned off through the `VideoCaptureParams.adaptOutputToDimensions`

- Add separate default capture/publish options for screenshare tracks - [#552](https://github.com/livekit/client-sdk-android/pull/552) ([@davidliu](https://github.com/davidliu))

### Patch Changes

- Add AudioPresets and increase default audio max bitrate to 48kbps - [#551](https://github.com/livekit/client-sdk-android/pull/551) ([@davidliu](https://github.com/davidliu))

- Fix crash when setting publishing layers - [#559](https://github.com/livekit/client-sdk-android/pull/559) ([@davidliu](https://github.com/davidliu))

- Added VideoFrameCapturer for pushing video frames directly - [#538](https://github.com/livekit/client-sdk-android/pull/538) ([@davidliu](https://github.com/davidliu))

- Fix surface causing null pointer exception on some devices - [#544](https://github.com/livekit/client-sdk-android/pull/544) ([@KasemJaffer](https://github.com/KasemJaffer))

- Update Kotlin dependency to 1.9.25 - [#552](https://github.com/livekit/client-sdk-android/pull/552) ([@davidliu](https://github.com/davidliu))

## 2.10.0

### Minor Changes

- Implement custom audio mixing into audio track - [#528](https://github.com/livekit/client-sdk-android/pull/528) ([@davidliu](https://github.com/davidliu))

- Update to webrtc-sdk 125.6422.06.1 - [#528](https://github.com/livekit/client-sdk-android/pull/528) ([@davidliu](https://github.com/davidliu))

- Implement screen share audio capturer - [#528](https://github.com/livekit/client-sdk-android/pull/528) ([@davidliu](https://github.com/davidliu))

## 2.9.0

### Minor Changes

- Implement LocalAudioTrack.addSink to receive audio data from local mic - [#516](https://github.com/livekit/client-sdk-android/pull/516) ([@davidliu](https://github.com/davidliu))

- Implement client metrics - [#511](https://github.com/livekit/client-sdk-android/pull/511) ([@davidliu](https://github.com/davidliu))

### Patch Changes

- Properly dispose peer connection on RTC thread - [#506](https://github.com/livekit/client-sdk-android/pull/506) ([@davidliu](https://github.com/davidliu))

- Documentation updates for LocalParticipant methods - [#510](https://github.com/livekit/client-sdk-android/pull/510) ([@davidliu](https://github.com/davidliu))

- Initialize WebRTC library only once - [#508](https://github.com/livekit/client-sdk-android/pull/508) ([@davidliu](https://github.com/davidliu))

## 2.8.1

### Patch Changes

- Fix local video tracks not rendering processed frames - [#495](https://github.com/livekit/client-sdk-android/pull/495) ([@davidliu](https://github.com/davidliu))

- Add utility class NoDropVideoProcessor to force video processing while not connected - [#495](https://github.com/livekit/client-sdk-android/pull/495) ([@davidliu](https://github.com/davidliu))

- More fixes for crashes caused by using disposed track - [#497](https://github.com/livekit/client-sdk-android/pull/497) ([@davidliu](https://github.com/davidliu))

## 2.8.0

### Minor Changes

- Implement LocalTrackSubscribed event - [#489](https://github.com/livekit/client-sdk-android/pull/489) ([@davidliu](https://github.com/davidliu))

- Add first and last received times to TranscriptionSegment - [#485](https://github.com/livekit/client-sdk-android/pull/485) ([@davidliu](https://github.com/davidliu))

### Patch Changes

- More guarding of rtc api usages to prevent crashes - [#488](https://github.com/livekit/client-sdk-android/pull/488) ([@davidliu](https://github.com/davidliu))

## 2.7.1

### Patch Changes

- Noisily log when a VideoRenderer is used without initializing it first - [#482](https://github.com/livekit/client-sdk-android/pull/482) ([@davidliu](https://github.com/davidliu))

- Fix NPE in RegionProvider when host can't be determined - [#482](https://github.com/livekit/client-sdk-android/pull/482) ([@davidliu](https://github.com/davidliu))
