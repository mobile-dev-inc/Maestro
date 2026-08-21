# device-core integration

Maestro is the harness, Orchestra is its flow engine, and [device-core](https://github.com/mobile-dev-inc/maestro-device-core) is its device engine. `maestro-orchestra` runs every device verb — launch, tap, assertVisible — through a seam called `DeviceGateway`, implemented over device-core's published api and consumed from `mavenLocal`. device-core is still filling that api in verb by verb; anything it hasn't built yet throws `NotImplemented` at the seam.

This doc covers running Maestro against a local device-core build, running the e2e smoke, how the two are wired together, and what to do when device-core ships a new verb.

## Setup

device-core is consumed as a Maven artifact, not a source dependency (the two repos build on different JVMs, so a Gradle composite build isn't an option). You build device-core, publish it to `mavenLocal`, and point Maestro at that version. The version is **not** hand-edited — it lives in a gitignored `devicecore.version` file that a script writes, and `maestro-orchestra/build.gradle.kts` reads.

Clone both repos, then from the Maestro repo root:

```sh
DEVICECORE_DIR=/path/to/maestro-device-core ./scripts/dev-setup.sh
```

That publishes device-core's `implementation` and `drivers-core` artifacts to `mavenLocal`, writes the resolved version into `devicecore.version`, builds the Maestro CLI, and prints the binary path plus copy-pasteable run commands.

`-x buildMcpViewer` is baked into the scripts because the vite/MCP-viewer build step is currently broken in local dev — drop it once that's fixed.

### Daily loop

After editing device-core:

```sh
./scripts/devicecore-sync.sh /path/to/maestro-device-core   # or set $DEVICECORE_DIR once and run it bare
./gradlew :maestro-cli:installDist -x buildMcpViewer --refresh-dependencies
```

Keep `--refresh-dependencies`: while you iterate with uncommitted device-core changes the version string stays `0.1.0-<sha>-dirty`, and Gradle caches that fixed version — without the flag you'd rebuild against a stale jar. A committed device-core change gets a new sha, so the version changes and the flag is moot.

## Running the e2e tests

The device engine needs a real device, and the interaction smoke runs against the **native Wikipedia app**, not the Flutter demo app. device-core reads the native platform accessibility trees (iOS XCUI label/title/value, the Android accessibility tree); a Flutter app publishes its widgets in a separate Flutter semantics tree that device-core doesn't read, so plain Flutter widgets never resolve for a tap. The native Wikipedia app is where text/id taps actually land. (The Flutter demo smoke at `e2e/demo_app/.maestro/devicecore_smoke.yaml` is therefore `launchApp`-only — the one device-core verb that works on a Flutter app.)

Boot a device, then get the Wikipedia app and its flows from GCS via the e2e scripts (the iOS Wikipedia build needs iOS >= 16.6):

```sh
cd e2e
./download_apps ios      # or: ./download_apps android
./install_apps ios       # installs onto the booted sim / connected device
```

Then run a smoke through the CLI you built above (`maestro-cli/build/install/maestro/bin/maestro`):

```sh
# iOS  (device-run GREEN)
xcrun simctl list devices booted                  # note the udid; `open -a Simulator` to watch it
maestro-cli/build/install/maestro/bin/maestro test \
  e2e/workspaces/wikipedia/devicecore-smoke-ios.yaml -p ios --udid <udid>

# Android  (authored; run it against an emulator/device)
adb devices                                       # note the serial, e.g. emulator-5554
maestro-cli/build/install/maestro/bin/maestro test \
  e2e/workspaces/wikipedia/devicecore-smoke-android.yaml -p android --udid <serial>
```

Both smokes use only device-core-served verbs and treat a successful tap as the assertion (`Outcome.Acted` proves the element was found and actionable).

The iOS launch race. device-core's iOS `launchApp` returns **before** the UI renders, and there is no device-core-served iOS wait verb yet (`assertVisible` / `waitForAnimationToEnd` are `NotImplemented` on the iOS seam). So a bare tap right after `launchApp` races the cold launch and resolves `Absent`. The iOS smoke bridges this with an orchestration-level `retry:` block around the first tap — it re-runs until the UI has rendered. Drop the `retry:` once device-core ships an iOS wait/settle verb. On Android there's no such race: `assertVisible` is backed by `waitFor`, which polls internally, so it absorbs the launch race with no `retry:` wrapper.

Write new flows using only the verbs device-core serves (see the burn-down below). A flow that hits an unbuilt verb throws `NotImplemented` naming that verb, which propagates out of the run — it doesn't fail the assertion, it stops the flow.

## Architecture: how Maestro consumes device-core

Everything lives in `maestro-orchestra/src/main/java/maestro/orchestra/devicecore/`. The seam has two sides and a translation layer between them:

```
Orchestra ──DeviceGateway──▶ RealDeviceGateway ──DeviceProvider──▶ device-core ──▶ device
flow control  (Maestro           translation +      (device-core        real verbs
              vocabulary)         error mapping       vocabulary)
```

- **`DeviceGateway`** (`DeviceGateway.kt`) is the interface Orchestra depends on. It speaks Maestro's vocabulary — `ElementSelector`, `MaestroException`, verbs like `tap` / `assertVisibility`. It enumerates the **full** device surface Orchestra needs up front; every verb device-core hasn't built yet has a default body on the interface itself that throws `MaestroException.NotImplemented("… does not yet implement <verb>")`. So "which verbs are overridden in `RealDeviceGateway`" *is* the burn-down map — zero un-overridden verbs means the migration is done.

- **`RealDeviceGateway`** (same file) implements the interface over device-core's `DeviceProvider`. It overrides only the verbs device-core actually serves today — `connect`, `close`, `launchApp`, `tap`, `assertVisibility` — and inherits the throwing default for the rest. It's constructed with a `providerFactory: (Platform) -> DeviceProvider`, which the default wires to device-core's Android/iOS providers.

- **Translation lives inside `RealDeviceGateway` and nowhere else.** The rest of `maestro-orchestra` never sees a `dev.mobile.devicecore.*` type. Three helpers do the work:
  - `SelectorTranslator` — Maestro's `ElementSelector` → device-core's `Selector` (`.Text` / `.Id` / `.Nth`); throws `NotImplemented` on a selector field device-core can't serve.
  - `WaitOutcomeVerdict` — device-core's waited `ActionEvidence.Outcome` → a pass/fail verdict (`Acted` = pass; `Absent`/`Blocked` = `AssertionFailure`; `Crashed` = `AppCrash`).
  - `DeviceCoreErrorMapper` — device-core's `Outcome` / typed errors → Maestro's exception taxonomy. Its `mapInfraThrow` rethrows `CancellationException` **before** any mapping, so a coroutine cancellation is never laundered into a wrong device verdict; a raw `NotImplementedError` (device-core's roadmap throws) becomes a clean `MaestroException.NotImplemented`.

For example, `tap` translates the selector, calls `screen.locatorFor(sel).tap()`, and hands the resulting `Outcome` to `DeviceCoreErrorMapper.tapOutcomeToException`; an infra throwable goes through `mapInfraThrow`. `assertVisibility` is a **waited** verb of the same shape: VISIBLE calls `screen.locatorFor(sel).waitFor(timeoutMs)` and reads the verdict off the returned `Outcome` via `WaitOutcomeVerdict` — never `inspect()`; NOT_VISIBLE throws `NotImplemented` (device-core ships no `waitFor(GONE)` verb yet).

Fakes exploit the throwing-default design: a test fake overrides only the handful of verbs it exercises and inherits `NotImplemented` for the ~35 it doesn't — no fake re-types the whole surface.

## Implementing a new verb

When device-core ships a verb — say `inputText` — here's how it becomes usable and tested in Maestro.

**1. Publish the new device-core build.** `./scripts/devicecore-sync.sh` picks up the new sha and rebuilds Maestro against it. `RealDeviceGateway` still inherits the `NotImplemented` default for the verb until you override it.

**2. Override the verb in `RealDeviceGateway`.** Replace the inherited default with a real override that follows the same shape as `tap` / `assertVisibility`:
- translate any Maestro selector/argument into device-core's vocabulary (extend `SelectorTranslator` if the verb needs a selector field it doesn't handle yet),
- call the device-core operation (`screen` / `device`), bridging the suspend call with `runBlocking` as the existing verbs do,
- map the result — a device-core `Outcome`/evidence → a Maestro verdict/exception, and any infra throwable through `DeviceCoreErrorMapper.mapInfraThrow` (never catch `Throwable` and map it yourself; that's where cancellation gets laundered).

Adding the override removes the verb from the `NotImplemented` surface — that's one entry burned down.

**3. Test it in two tiers** (the team default: unit-test the branchy pure logic, integration-test everything else):
- **Tier 1 — unit tests** for any new *pure* translation logic. If the verb adds a selector field or a new outcome→exception case, extend `SelectorTranslatorTest` / `DeviceCoreErrorMapperTest` / `WaitOutcomeVerdictTest` — call the function, assert the output, no mocks. This is where the real branching bugs live, so keep these exhaustive.
- **Tier 2 — command-driven integration tests** that drive a real `MaestroCommand` through the whole stack and fake device-core at the `DeviceProvider` seam. Use the `FlowMatrix` harness with a `FakeDeviceProvider`, seeding evidence via `DeviceCoreEvidence`. Assert one of two ways: the device-core call the command produced (a `tapOn` produced `tap(Selector.Id("…"))`), or the command's result given a seeded response. Assert the *meaningful* device-core call, not every incidental one, or the tests turn brittle.

**4. Re-enable the recovered tests that were blocked on this verb.** The `IntegrationTest` recovery left `@Disabled` cases wherever a flow's only assertion sat behind an unbuilt verb — grep the test tree for `@Disabled(".*<verb>")` and the `// TODO(<verb>)` next to it, which records the original's intended assertion. Turning the verb on should let those port cleanly; drop the `@Disabled` and restore the real assertion from the TODO.

The north star is zero un-overridden verbs on `DeviceGateway` and zero `@Disabled` recovery tests — at which point device-core serves everything Orchestra asks of it.

## Evaluating a version bump

device-core is the source of truth. A semantic change (like `nth` withdrawing its geometric
ordering) is new truth to translate faithfully, not a regression to revert. Two mechanics:

- **Diff against the exact pinned sha, never an on-disk branch.**
  `git -C <device-core> diff <pinned-sha>..<new-sha> -- implementation/src/main/kotlin/dev/mobile/devicecore/prototype/api/`
  reading the pinned sha from `devicecore.version`. An on-disk branch can be ahead of or behind
  what Maestro actually consumes — auditing against the wrong branch produced five false "stale"
  alarms during this bump.

- **Use the type → seam reverse index to find what a changed type touches:**
  - `ElementEvidence` / `ActionEvidence` / `Outcome` → the verdict logic (`WaitOutcomeVerdict`,
    `DeviceCoreErrorMapper.tapOutcomeToException`).
  - device-core error types (`DeviceEnvError`, `DeviceResolutionFailure`, `InjectionUnavailable`,
    `NotImplementedError`) → `DeviceCoreErrorMapper`.
  - `Selector` / `Match` → `SelectorTranslator` and `Screen.locatorFor`.

  A green compile and green tests are the STRUCTURAL check (did the API shape change), not the
  SEMANTIC one (did a verb's meaning change). Read the diff's decision notes for the latter.
