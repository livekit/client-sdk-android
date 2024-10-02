# client-sdk-android

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
