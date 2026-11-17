---
name: bump-ios-version
description: Use when a new iOS or Xcode version ships and Maestro must support it, or when CI's iOS runtime/Xcode pin needs moving to a newer version.
---

# Bump iOS Version

Module roles: [`AGENTS.md`](../../../AGENTS.md). Branch off `main`; one commit per step.

## Steps

1. **Ask scope:** fix Maestro for users on the new iOS, move CI to it, or both.
2. **Demo app first.** On the new Xcode: `cd e2e/demo_app && flutter build ios --simulator`, install and launch on a new-iOS simulator. If it fails to build or crashes at launch, fix the app before testing Maestro. Usual causes (example: PR #3758, Xcode 27):
   - deployment target below the new Xcode's floor (Runner, `Podfile` and its `post_install` hook)
   - lifecycle Apple now requires (e.g. UIScene); move channel setup out of `didFinishLaunching`
   - plugin majors; Flutter version in `pubspec.yaml`
   - a Flutter bump drags the Android toolchain (Gradle, AGP, Kotlin): build the APK on Java 17 and run `e2e/run_tests android`
3. **XCTest runner.** Build with `./gradlew :maestro-ios-driver:buildIosDriver` (its script exits unless the checkout folder is named `Maestro`). Raise `IPHONEOS_DEPLOYMENT_TARGET` if below the new floor. Never commit the regenerated `maestro-driver-ios*.zip`.
4. **Baseline.** On a simulator of the new iOS **and** one of the current CI iOS (`RUNTIME` in `test-e2e.yaml`): `cd e2e && ./download_apps ios && ./install_apps ios`, install step 2's demo app build over it, then run `./run_tests ios` and the demo app flows, which `run_tests` skips on iOS:
   `maestro --platform ios test --include-tags passing --exclude-tags android,web,android_device_configuration,ios_device_configuration --debug-output ~/.maestro/tests/demo_app/passing demo_app/.maestro` Only failures new to the new iOS are regressions.
5. **Fix** each regression in `maestro-ios-xctest-runner/`, `maestro-ios-driver/` or `maestro-client/`, with a unit test. Never in `failing/` flows or workflow band-aids. Ask before each fix. `diagnose-maestro-failure` is Android-only; diagnose iOS from `~/.maestro/tests/` yourself.
6. **CI pins** (only if scope includes CI, and only if the runner image ships the new Xcode): `RUNTIME`/`DEVICE_TYPE`/`runs-on` in `test-e2e.yaml`, and the `Xcode_<ver>.app` path in `rebuild-ios-drivers.yaml` and `update-drivers.yaml`.
7. **Validate.** Ask before pushing. Open a PR; green means `Test on iOS` and `Test on Android` pass. CI builds the demo app on the old Xcode but runs no demo app flows on iOS, so put step 4's local results in the PR's Verification.
   <!-- TODO: once CI runs demo_app flows on iOS (`e2e/workspaces.txt`), drop the local demo app run in step 4 and rely on `Test on iOS`. -->


## Stop and ask when

- the runner image lacks the new Xcode
- a fix needs a workflow patch or breaks the current CI iOS
- an earlier iOS bump PR is still open (stack on it or replace it?)
- the same flow fails after 3 fix attempts
