# Contributing
We appreciate any pull requests for changes you may have in mind! Here are some tips and instructions you should follow before we can accept your PR.

* Consult the [Dev Environment](https://github.com/livekit/client-sdk-android?tab=readme-ov-file#dev-environment) instructions for getting the repo set up on your computer. The contained projects may not compile otherwise.

* Add a changeset file which explains the changes contained in the PR.

  In the root folder, execute the following commands:
  ```
  pnpm install
  pnpm changeset
  ```

  Follow the instructions on screen to create the changeset file.

* Format your code using `./gradlew spotlessApply`.

* On your first pull request, the CLA Assistant bot will give you a link to sign this project's Contributor License Agreement, required to add your code to the repository. This license is non-optional and we cannot accept any PRs from contributors who have not signed the CLA.
