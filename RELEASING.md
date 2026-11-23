# Releasing client-sdk-android

This repository releases with [verwalter](https://github.com/livekit/verwalter).
The commands that are the same in every repository are in the
[verwalter cheat sheet](https://github.com/livekit/verwalter/blob/main/docs/cheat-sheet.md).
This file holds what is specific to this repository.

## Packages

| Package | Tag | Goes to |
|---|---|---|
| `client-sdk-android` | `v<version>` | Maven Central: `io.livekit:livekit-android`, `livekit-android-camerax`, `livekit-android-track-processors`, `livekit-android-test` |

The version is in `gradle.properties` (`VERSION_NAME`) and `README.md`.
Do not change it by hand. The release PR changes it.

## Workflows

| File | Starts when | Does |
|---|---|---|
| `changeset-check.yml` | A PR is opened or updated | Fails a PR without a changeset. The `internal` label skips the check. |
| `release-pr.yml` | A push to `main` | Keeps the release PR `release/main` current. |
| `release.yml` | The release PR merges | Tags, creates the GitHub release, publishes to Maven Central, uploads the docs, notifies `livekit/components-android`. |
| `android.yml` | A push to `main` | Publishes `<next patch version>-SNAPSHOT` to the Sonatype snapshot repository. |

## Release

1. Merge the release PR.
2. Watch the run of `release.yml`: `gh run list --workflow release.yml --limit 1`.

A release is complete when:

- the tag `v<version>` has a public GitHub release
- `https://repo1.maven.org/maven2/io/livekit/livekit-android/<version>/` exists.
  Maven Central can show a new version 30 minutes late.

## Pre-release

Not used. An rc on Maven Central is a permanent release.

## A release failed

Find the cause first. The paths by cause are in the
[recovery section](https://github.com/livekit/verwalter/blob/main/docs/release-workflows.md#recovery).

| Situation | Command |
|---|---|
| A job failed for a transient reason | `gh run rerun <run-id> --failed` |
| The workflow file needed a fix, and the fix is on `main` | `gh workflow run release.yml -f ref=<tag>` |
| The released commit is wrong | `verwalter release --abandon <tag>`, then a changeset and the next version |

While Sonatype still processes an upload, do not re-run the publish job.
The existence check reads `repo1.maven.org`, which shows a new version
late. A second upload of the same version fails at Sonatype.

## After a release

`release.yml` dispatches `update-livekit-version` to
`livekit/components-android`. Merge the PR that it opens there.
