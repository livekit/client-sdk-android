# Contributing
We appreciate any pull requests for changes you may have in mind! Here are some tips and instructions you should follow before we can accept your PR.

* Consult the [Dev Environment](https://github.com/livekit/client-sdk-android?tab=readme-ov-file#dev-environment) instructions for getting the repo set up on your computer. The contained projects may not compile otherwise.

* Add a changeset file which explains the changes contained in the PR.

  Add a file such as `.changeset/my-change.md`:
  ```md
  ---
  "client-sdk-android": patch
  ---

  One sentence for users of the SDK.
  ```

  Use `patch` for a fix and `minor` for a new API. If a PR has no changeset, the
  changeset check comments with a link to a prefilled file. With
  [verwalter](https://github.com/livekit/verwalter) installed, you can also run
  `verwalter changeset --level patch`.

* Format your code using `./gradlew spotlessApply`.

* On your first pull request, the CLA Assistant bot will give you a link to sign this project's Contributor License Agreement, required to add your code to the repository. This license is non-optional and we cannot accept any PRs from contributors who have not signed the CLA.
