# client-sdk-android

## Setup

Requires `protocol` repo to exist at the same level as this repo to generate protobufs correctly.

### Optional (Dev convenience)

1. Download webrtc sources from https://webrtc.googlesource.com/src
2. Add sources to Android Studio by pointing at the `webrtc/sdk/android` folder.

## Publishing releases

1. Ensure you have your `.gradle/gradle.properties` filled with the requisite credentials:

````
nexusUsername=<sonatype username>
nexusPassword=<sonatype password>
signing.keyId=<signing key id>
signing.password=<signing key password>
signing.secretKeyRingFile=<signing pgp key path>
````

2. Update `VERSION_NAME` in `gradle.properties` to reflect the release version.
3. Run `gradle publish closeAndReleaseRepository` to upload to maven.
