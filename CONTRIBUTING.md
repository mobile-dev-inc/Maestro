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

## Testing

There are 3 ways to test your changes:

- Integration tests
  - Run them via `./gradlew :maestro-test:test` (or from IDE)
  - Tests are using real implementation of most components except for `Driver`. We use `FakeDriver` which pretends to be a real device.
- Manual testing
  - Run `./maestro` instead of `maestro` to use your local code.
- Unit tests
  - All the other tests in the projects. Run them via `./gradlew test` (or from IDE) 

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

## How to

### Add new command

Follow these steps:

- Define a new command in `Commands.kt` file, implementing `Command` interface.
- Add a new field to `MaestroCommand` class, following the example set by other commands.
- Add a new field to `YamlFluentCommand` to map between yaml representation and `MaestroCommand` representation.
- Handle command in `Orchestra` class.
  - If this is a new functionality, you might need to add new methods to `Maestro` and `Driver` APIs.
- Add a new test to `IntegrationTest`.