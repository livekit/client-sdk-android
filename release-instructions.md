## Publishing releases

1. Ensure you have your `.gradle/gradle.properties` filled with the requisite credentials:

````
nexusUsername=<sonatype username>
nexusPassword=<sonatype password>
signing.keyId=<signing key id>
signing.password=<signing key password>
signing.secretKeyRingFile=<signing pgp key path>
````

2. Update `VERSION_NAME` in `gradle.properties` to reflect the release version. (Remove "-SNAPSHOT" when releasing.)
3. Run `gradle publish` to upload to Sonatype.
4. Run `gradle closeAndReleaseRepository` to release to Maven (do not combine with previous command as ordering isn't guaranteed.)
5. Update `VERSION_NAME` in `gradle.properties` to prepare for next release version.
