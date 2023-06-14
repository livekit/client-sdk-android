# Releasing

1. In the top-level gradle.properties file, update the `VERSION_NAME` property. This will usually just be removing the `-SNAPSHOT` suffix.
1. Commit and push changes.
1. Tag the release: `git tag v[VERSION_NAME]`. Make sure there is a `v` prefix at the front of the tag. This indicates to the CI that this is a release tag.
1. Push tags: `git push --tags`
1. Confirm that the `publish` github action is completed successfully.
1. Add a new GitHub release with change logs.
1. Prepare the version for the next release by bumping the `VERSION_NAME` property and adding the `-SNAPSHOT` suffix.
1. Additionally update the version names in README.md.
