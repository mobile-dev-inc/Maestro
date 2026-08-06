# device-core `assertVisible` — interleaved confirm on a real app

One `maestro test` run against a real app (Wikipedia) that interleaves both drivers: the literal-text
`assertVisible` commands are decided by maestro-device-core's `inspect()`, and everything else
(`launchApp`, `tapOn`, a regex `assertVisible`) runs on legacy Maestro — all in the same run, on one
booted simulator. device-core's on-device server is **self-provisioned in code** from the jar-embedded
assembly (device-core PR #84), so there's no manual server bring-up.

Routing lives at `Orchestra.assertConditionCommand` and is gated on `MAESTRO_DEVICECORE_ASSERT=1` +
iOS. A standalone `assertVisible` whose selector is a single literal string (no regex metacharacters,
no id, no relational/state constraint) diverts to device-core; everything else stays on legacy.

## What lands where in `flow.yaml`

| command | driver |
|---|---|
| `launchApp: { clearState: true }` | legacy |
| `assertVisible: "Skip"` | **device-core** (literal) |
| `assertVisible: "Skip.*"` | legacy (regex metachars → not routed; matches "Skip") |
| `assertVisible: "Next"` | **device-core** (literal) |
| `tapOn: "Skip"` | legacy |

## Prerequisites

- **The device-core `#84` jar** (embeds the driver-server binaries). Two ways:
  - **Local (lockstep):** from the device-core checkout, `./gradlew publishToMavenLocal`. Maestro
    resolves it local-first from `~/.m2` — no token needed.
  - **Remote:** a classic `read:packages` PAT in `~/.gradle/gradle.properties` (`gpr.user` /
    `gpr.read.token`), once a `#84` build has been published to GitHub Packages from a Mac. (The
    ubuntu publish workflow can't build the native binaries yet — see the follow-up.)
  No device-core checkout is needed to *build* Maestro; the jar carries everything.
- **A booted simulator.** e.g. `xcrun simctl boot <UDID>`.
- **Wikipedia installed on it:**
  ```bash
  cd e2e && ./download_apps ios && ./install_apps ios
  ```
  (Installs `org.wikimedia.wikipedia` from the e2e apps bucket.)

There is **no** manual driver-server start and **no** conformance fixture — `connect()` brings up the
8792 server itself on the first routed assert.

## Build the CLI

```bash
./gradlew :maestro-cli:installDist -x buildMcpViewer
export PATH="$PWD/maestro-cli/build/install/maestro/bin:$PATH"
```

## Run

```bash
export MAESTRO_DEVICECORE_ASSERT=1
maestro test e2e/workspaces/devicecore/flow.yaml --device <UDID>
```

Confirm which asserts device-core decided:

```bash
grep -c "device-core decided" ~/.maestro/tests/<latest>/maestro.log   # expect 2 (Skip, Next)
```

Expected: the flow passes; exactly the two literal `assertVisible`s show `device-core decided`, the
regex one does not (it ran on legacy), and both the legacy XCUITest runner and device-core's 8792
server are alive on the sim during the run.

### Negative control

`flow-negative.yaml` asserts an absent literal after a present one:

```bash
maestro test e2e/workspaces/devicecore/flow-negative.yaml --device <UDID>   # exits 1
```

device-core returns `Absent` → `verdict=false` → the flow fails at that step, so a green `flow.yaml`
was never a pass-through.

## Known-hacky bits (deliberate, for this confirm)

- **The server is leaked, then reused.** The current router opens a device-core connection per assert
  and never closes it, so the first routed assert launches the 8792 server and leaves it up; later
  routed asserts reuse it. Fine for a single run; a proper open-once/close-at-teardown lifecycle is
  deferred.
- **The app-under-test comes from a global property** (`devicecore.ios.bundleId`, set from the flow's
  `appId`), which pins one connection to one app. Fine here (single app); the coherent model for
  specifying the app-under-test is being designed separately.
