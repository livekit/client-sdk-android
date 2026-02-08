# LiveKit Android SDK

## Commands

Supported platforms: Android (minimum API level 21)

- Assemble: `./gradlew assemble`
- Run tests: `./gradlew test`
- Install example app: `./gradlew sample-app-compose:installDebug`

## Architecture

The SDK is provided through the `livekit-sdk-android` module.

```
livekit-sdk-android/src/main/java/io/livekit
├── annotations/           # Annotations for marking APIs (e.g. @Beta)
├── audio/                 # AudioHandler, AudioProcessingController
├── coroutines/            # Utility methods relating to coroutines
├── dagger/                # Dependency injection internal to LiveKit SDK
├── e2ee/                  # End-to-end encryption
├── events/                # RoomEvent, TrackEvent, ParticipantEvent
├── room/                  # Room management
│   ├── datastream/        # Incoming/outgoing datastream IO
│   ├── participant/       # LocalParticipant, RemoteParticipant
│   ├── track/             # AudioTrack, VideoTrack, TrackPublication
│   └── types/             # Externally predefined types
├── token/                 # TokenSource implementations for auth
├── util/                  # Generic utility methods, logging, FlowDelegate
└── webrtc/                # WebRTC helper classes
```

Key components:

- `LiveKit` - main entry point; creates a `Room` object.
- `Room` - primary class that users will interact with; manages connection state, participants, and
  tracks
- `Participant` - base class for `LocalParticipant`/`RemoteParticipant`; holds track publications
- `SignalClient` - WebSocket connection to LiveKit server
- `FlowDelegate` - provides the consumption of class members marked with `@FlowObservable` as a
  `Flow` through the `flow` property extension.

## WebRTC

WebRTC handles the actual media transport (audio/video/data) between participants. The SDK abstracts
WebRTC complexity behind `Room`, `Participant`, and `Track` APIs while LiveKit server coordinates
signaling.

Key classes:

- `PeerConnectionTransport` - wraps a `PeerConnection`; handles ICE candidates, SDP offer/answer
- `RTCEngine` - integrates the SignalClient and PeerConnectionTransport into a consolidated
  connection
- `io.livekit.webrtc` package - convenience extensions on WebRTC types

Threading:

- All WebRTC API calls must use `executeOnRTCThread`, `executeBlockingOnRTCThread`, or
  `launchBlockingOnRTCThread` for thread safety
- Each call requires a `RTCThreadToken` that manages the thread execution requests

## Dependency Injection

This library makes extensive use of Dagger to provide dependency injection throughout the codebase.

- Dependency needs should be met through injecting into an `@Inject` or `@AssistedInject` annotated
  constructor
- Variable dependencies (such as IDs, varying implementations) can be provided through the use of an
  `@AssistedFactory`

## FlowObservable

The SDK heavily relies on `@FlowObservable` class members, which allow them to be used as regular
variables,
while also allowing them to be observed as a `Flow`. This is especially useful for Android Compose
projects,
as this allows them to be converted to `State` objects and update the UI appropriately.

```kotlin
val identity = participant.identity // regular access
val identityFlow = participant::identity.flow // as a flow
val identityState = participant::identity.flow.collectAsState() // as a state
```

A `@FlowObservable` class member can be created using the `flowDelegate` property delegate:

```kotlin
@FlowObservable
@get:FlowObservable
var identity: Identity? by flowDelegate(identity)
```

## Testing

Unit tests are provided through the `livekit-android-test` module.

- `io.livekit.android.test.mock` package - mocks and fakes
- `MockE2ETest` - the base class for when testing `Room` behavior

## Using Kotlin

### Concurrency and State

- The SDK uses coroutines for background thread processing.
- Classes should create and own their own coroutine scope if they use coroutines.

### Error Handling

- Crashing consumer code via unchecked exceptions is **not allowed**
- `assert()`/`Preconditions` should be avoided
- Prefer returning `Result` rather than throwing exceptions.
- Methods that can throw exceptions must be annotated with `@throws` in the documentation.
- For recoverable errors, consider defensive programming first (retry, backoff, graceful failure)
- Anticipate invalid states at compile time using algebraic data types, typestates, etc.

### Coding Style

- Follow official Kotlin code style
- Consistency across features is more important than latest syntactic sugar
- Run `./gradlew spotless` to check for code style; **do not** introduce new warnings
- Deprecation warnings are allowed in public APIs; do not fix them
- `// Code comments` should be used sparingly; prefer better naming/structuring
- Do not add trivial "what" comments like `// Here is the change`
- Kdoc comments for **every** public API
- Add short code examples for new APIs to the entry point (e.g., `Room` class)
- Use `LKLog` for logging

<skills_system priority="1">

## Available Skills

<!-- SKILLS_TABLE_START -->
<usage>
When users ask you to perform tasks, check if any of the available skills below can help complete the task more effectively. Skills provide specialized capabilities and domain knowledge.

How to use skills:

- Invoke: `npx openskills read <skill-name>` (run in your shell)
    - For multiple: `npx openskills read skill-one,skill-two`
- The skill content will load with detailed instructions on how to complete the task
- Base directory provided in output for resolving bundled resources (references/, scripts/, assets/)

Usage notes:

- Only use skills listed in <available_skills> below
- Do not invoke a skill that is already loaded in your context
- Each skill invocation is stateless
  </usage>

<available_skills>

<skill>
<name>android-coroutines</name>
<description>Authoritative rules and patterns for production-quality Kotlin Coroutines onto Android. Covers structured concurrency, lifecycle integration, and reactive streams.</description>
<location>project</location>
</skill>

<skill>
<name>android-gradle-logic</name>
<description>Expert guidance on setting up scalable Gradle build logic using Convention Plugins and Version Catalogs.</description>
<location>project</location>
</skill>

<skill>
<name>android-testing</name>
<description>Comprehensive testing strategy involving Unit, Integration, Hilt, and Screenshot tests.</description>
<location>project</location>
</skill>

<skill>
<name>gradle-build-performance</name>
<description>Debug and optimize Android/Gradle build performance. Use when builds are slow, investigating CI/CD performance, analyzing build scans, or identifying compilation bottlenecks.</description>
<location>project</location>
</skill>

<skill>
<name>kotlin-concurrency-expert</name>
<description>Kotlin Coroutines review and remediation for Android. Use when asked to review concurrency usage, fix coroutine-related bugs, improve thread safety, or resolve lifecycle issues in Kotlin/Android code.</description>
<location>project</location>
</skill>

</available_skills>
<!-- SKILLS_TABLE_END -->

</skills_system>
