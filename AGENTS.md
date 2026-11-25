# Agent conventions for Heimdall

## Release flow (do not wait to be asked)

When a planned change is finished and its checks pass, publish it without asking:

1. Bump `heimdall.versionName` / `heimdall.versionCode` in `gradle.properties` (patch bump for fixes,
   minor for features).
2. Run the local gates: `spotlessCheck`, `detekt`, `:app:lintDebug`, `:app:testDebugUnitTest`,
   `:acceptance:test`, and `:app:assembleDebug -Pheimdall.abis=arm64-v8a`.
3. Commit the change, push `main`, then push an annotated `v<versionName>` tag.
4. The `release` workflow builds and publishes both APKs for a `v*` tag; `ci`, `native`,
   `privacy-gate` and `instrumented` run on the `main` push. Do not publish a GitHub release by hand.
5. Wait for the workflow run and confirm the release assets before reporting success.

Never push or tag when the checks fail, and never ship a release without the privacy gate green
(`android.permission.INTERNET` must stay absent).
