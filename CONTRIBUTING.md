# Contributing to Maestro

Thank you for considering contributing to the project!

We welcome contributions from everyone and generally try to be as accommodating as possible. However, to make sure that your time is well spent, we separate the types of 
contributions in the following types:

- Type A: Simple fixes (bugs, typos) and cleanups
  - You can open a pull request directly, chances are high (though never guaranteed) that it will be merged.
- Type B: Features and major changes (i.e. refactoring)
  - Unless you feel adventurous and wouldn't mind discarding your work in the worst-case scenario, we advise to open an issue or a PR with a suggestion first where you will 
    describe the problem you are trying to solve and the solution you have in mind. This will allow us to discuss the problem and the solution you have in mind.

### Side-note on refactoring

Our opinion on refactorings is generally that of - don't fix it if it isn't broken. Though we acknowledge that there are multiple areas where code could've been structured in a 
cleaner way, we believe there are no massive tech debt issues in the codebase. As each change has a probability of introducing a problem (despite all the test coverage), be 
mindful of that when working on a refactoring and have a strong justification prepared. 

## Lead times

We strive towards having all public PRs reviewed within a week, typically even faster than that. If you believe that your PR requires more urgency, please contact us on a 
public Maestro Slack channel.

Once your PR is merged, it usually takes about a week until it becomes publicly available and included into the next release.

## Developing

### Requirements

Maestro's minimal deployment target is Java 8, and we strive to keep it this way
for as long possible, because our analytics indicate that (as of September 2024) many
users still use Java 8.

For development, you need to use Java 11 or newer.

If you made changes to the CLI, rebuilt it with `./gradlew :maestro-cli:installDist`. This will generate a startup shell
script in `./maestro-cli/build/install/maestro/bin/maestro`. Use it instead of globally installed `maestro`.

If you made changes to the iOS XCTest runner app, make sure they are compatible with the version of Xcode used by the GitHub Actions build step. It is currently built using the default version of Xcode listed in the macos runner image [readme][macos_builder_readme].
If you introduce changes that work locally but fail to build when you make a PR, check if you used a feature used in a newer version of Swift or some other new Xcode setting.

### Debugging

Maestro stores logs for every test run in the following locations:

- CLI Logs: `~/.maestro/tests/*/maestro.log`
- iOS test runner logs: `~/Library/Logs/maestro/xctest_runner_logs`

### Android artifacts

Maestro requires 2 artifacts to run on Android:

- `maestro-app.apk` - the host app. Does nothing.
- `maestro-server.apk` - the test runner app. Starts an HTTP server inside an infinite JUnit/UIAutomator test.

These artifacts are built by `./gradlew :maestro-android:assemble` and `./gradlew :maestro-android:assembleAndroidTest`, respectively.
They are placed in `maestro-android/build/outputs/apk`, and are copied over to `maestro-client/src/main/resources`.

### iOS artifacts

Maestro requires 3 artifacts to run on iOS:

- `maestro-driver-ios` - the host app for the test runner. Does nothing and is not installed.
- `maestro-driver-iosUITests-Runner.app` - the test runner app. Starts an HTTP server inside an infinite XCTest. 
- `maestro-driver-ios-config.xctestrun` - the configuration file required to run the test runner app.

These artifacts are built by the `build-maestro-ios-runner.sh` script. It places them in `maestro-ios-driver/src/main/resources`.

### Running standalone iOS XCTest runner app

The iOS XCTest runner can be run without Maestro CLI. To do so, make sure you built the artifacts, and then run:

```console
./maestro-ios-xctest-runner/run-maestro-ios-runner.sh
```

This will use `xcodebuild test-without-building` to run the test runner on the connected iOS device. Now, you can reach
the HTTP server that runs inside the XCTest runner app (by default on port 22087):

```console
curl -fsSL -X GET localhost:22087/deviceInfo | jq
```

<details>
<summary>See example output</summary>

```json
{
  "heightPoints": 852,
  "heightPixels": 2556,
  "widthPixels": 1179,
  "widthPoints": 393
}
```

</details>

```console
curl -fsSL -X POST localhost:22087/touch -d '
{
  "x": 150,
  "y": 150,
  "duration": 0.2
}'
```

```console
curl -sSL -X GET localhost:22087/swipe -d '
{
  "startX": 150,
  "startY": 426,
  "endX": 426,
  "endY": 350,
  "duration": 1
}'
```


### Artifacts and the CLI

`maestro-cli` depends on both `maestro-ios-driver` and `maestro-client`. This is how the CLI gets these artifacts.

## Testing

There are 3 ways to test your changes:

- Integration tests
  - Run them via `./gradlew :maestro-test:test` (or from IDE)
  - Tests are using real implementation of most components except for `Driver`. We use `FakeDriver` which pretends to be a real device.
- Manual testing
  - Run `./maestro` instead of `maestro` to use your local code.
- Unit tests
  - All the other tests in the projects. Run them via `./gradlew test` (or from IDE)

If you made changes to the iOS XCUITest driver, rebuild it by running `./maestro-ios-xctest-runner/build-maestro-ios-runner.sh`.

## Architectural considerations

Keep the following things in mind when working on a PR:

- `Maestro` class is serving as a target-agnostic API between you and the device.
  - `Maestro` itself should not know or care about the concept of commands.
- `Orchestra` class is a layer that translates Maestro commands (represented by `MaestroCommand`) to actual calls to `Maestro` API.
- `Maestro` and `Orchestra` classes should remain completely target (Android/iOS/Web) agnostic.
  - Use `Driver` interface to provide target-specific functionality.
  - Maestro commands should be as platform-agnostic as possible, though we do allow for exceptions where they are justified.
- Maestro CLI is supposed to be cross-platform (Mac OS, Linux, Windows).
- Maestro is designed to run locally as well as on Maestro Cloud. That means that code should assume that it is running in a sandbox environment and shouldn't call out or spawn 
  arbitrary processes based on user's input
  - For that reason we are not allowing execution of bash scripts from Maestro commands.
  - For that reason, `MaestroCommand` class should be JSON-serializable (and is a reason we haven't moved to `sealed class`)
- We do not use mocks. Use fakes instead (e.g. `FakeDriver`).

This graph (generated with [`./gradlew :generateDependencyGraph`][graph_plugin] in [PR #1834][pr_1834]) may be helpful
to visualize relations between subprojects:

![Project dependency graph](assets/project-dependency-graph.svg)

## How to

### Add new command

Follow these steps:

- Define a new command in `Commands.kt` file, implementing `Command` interface.
- Add a new field to `MaestroCommand` class, following the example set by other commands.
- Add a new field to `YamlFluentCommand` to map between yaml representation and `MaestroCommand` representation.
- Handle command in `Orchestra` class.
  - If this is a new functionality, you might need to add new methods to `Maestro` and `Driver` APIs.
- Add a new test to `IntegrationTest`.

[macos_builder_readme]: https://github.com/actions/runner-images/blob/main/images/macos/macos-14-Readme.md
[graph_plugin]: https://github.com/vanniktech/gradle-dependency-graph-generator-plugin
[pr_1834]: https://github.com/mobile-dev-inc/maestro/pull/1834
