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
4. **Baseline.** On a simulator of the new iOS **and** one of the current CI iOS (`RUNTIME` in `test-e2e.yaml`): `cd e2e && ./download_apps ios && ./install_apps ios`, then install step 2's demo app build over it and run `./run_tests ios`. Only failures new to the new iOS are regressions.
5. **Fix** each regression in `maestro-ios-xctest-runner/`, `maestro-ios-driver/` or `maestro-client/`, with a unit test. Never in `failing/` flows or workflow band-aids. Ask before each fix. `diagnose-maestro-failure` is Android-only; diagnose iOS from `~/.maestro/tests/` yourself.
6. **CI pins** (only if scope includes CI, and only if the runner image ships the new Xcode): `RUNTIME`/`DEVICE_TYPE`/`runs-on` in `test-e2e.yaml`, and the `Xcode_<ver>.app` path in `rebuild-ios-drivers.yaml` and `update-drivers.yaml`.
7. **Validate.** Ask before pushing. Open a PR; green means `Test on iOS` and `Test on Android` pass, which also proves the demo app still builds on the old Xcode.

## Stop and ask when

- the runner image lacks the new Xcode
- a fix needs a workflow patch or breaks the current CI iOS
- an earlier iOS bump PR is still open (stack on it or replace it?)
- the same flow fails after 3 fix attempts
