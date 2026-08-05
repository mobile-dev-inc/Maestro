# device-core `assertVisible` — runbook + captured proof

One real `assertVisible` inside a `maestro test` flow, on a booted iOS simulator, decided by
maestro-device-core's `inspect()` instead of legacy Maestro's own resolver. This directory holds
the two flows (`flow.yaml`, `flow-negative.yaml`) and, below, the exact commands to reproduce the
run plus the captured evidence from 2026-08-05.

Routing is off by default. It turns on only when `MAESTRO_DEVICECORE_ASSERT=1` and the target is a
literal string on an iOS flow — otherwise the assert runs on legacy, unchanged.

## Prerequisites

- **A `read:packages` token.** device-core's `prototype` jar resolves from a private GitHub
  Package (`maven.pkg.github.com/mobile-dev-inc/maestro-device-core`), not from a local checkout.
  Any authorized org member builds Maestro against it with a classic PAT carrying `read:packages`,
  in `~/.gradle/gradle.properties`:
  ```
  gpr.user=<your-github-username>
  gpr.read.token=<classic PAT with read:packages>
  ```
  You do **not** need a device-core checkout to build Maestro. (You do still need device-core's
  prebuilt iOS conformance bundles on the simulator for the live run — the driver server and the
  fixture app below.)
- **A booted simulator.** The captured run used iPhone 14 Pro, iOS 16.4,
  UDID `6921573F-D8AB-4AC7-A24C-BC700CD7345D`, Xcode 26.6.
  ```bash
  xcrun simctl boot <UDID>
  ```
- **device-core's driver server on 8792.** A UI-test bundle that parks on the run loop and answers
  `inspect()` over 8792:
  ```bash
  xcodebuild test-without-building \
    -xctestrun <device-core>/conformance/apps/ios-uikit/build/Build/Products/ConformanceDriverServer.xctestrun \
    -destination id=<UDID> &
  until nc -z 127.0.0.1 8792; do sleep 1; done
  ```
- **The fixture app, installed and staged over 8795.** The fixture's default screen is blank — it
  paints nothing until a `SCENARIO` command arrives on its 8795 control channel, and staging is
  in-process (it must survive without a relaunch). So install, launch, then stage:
  ```bash
  xcrun simctl install <UDID> \
    <device-core>/conformance/apps/ios-uikit/build/Build/Products/Debug-iphonesimulator/ConformanceUIKit.app
  xcrun simctl launch <UDID> dev.mobile.devicecore.conformance.uikit
  until nc -z 127.0.0.1 8795; do sleep 1; done
  printf 'SCENARIO static-text-unique\n' | nc -w4 127.0.0.1 8795   # -> OK  (paints "Order summary")
  ```
  This is why `flow.yaml` uses `launchApp: { stopApp: false }`: Maestro's iOS launch calls
  `simctl launch` without `--terminate-running-process`, so `stopApp: false` foregrounds the
  already-staged app and the "Order summary" screen survives to the assert. A relaunch would wipe it.

## Build

```bash
./gradlew :maestro-cli:installDist -x buildMcpViewer
```

`-x buildMcpViewer` skips the mcp-viewer npm task, which is broken on Node 20.9 (pre-existing and
unrelated to this run). The built CLI lands at `maestro-cli/build/install/maestro/bin/maestro`.

## Run

```bash
export MAESTRO_DEVICECORE_ASSERT=1
maestro test e2e/workspaces/devicecore/flow.yaml --device <UDID>
```

Then check which resolver decided it:

```bash
grep -c "device-core decided" ~/.maestro/tests/<latest>/maestro.log   # >0 = device-core, 0 = legacy
```

`flow.yaml` asserts `Order summary` — a unique literal at a known rect
(`x=122 y=160 w=148 h=26` pts on a `393x852` pt screen), so the router accepts it and device-core
resolves it on-screen.

## The target and its bounds

`Order summary`, discovered by staging `static-text-unique` and reading the fixture's `TRUTH`:

```
{"roles":[{"cls":"UILabel","name":"text","rect":{"h":26.33,"w":148.33,"x":122.33,"y":160}}],
 "screen":{"h":852,"scale":3,"w":393},"settled":true,"stem":"static-text-unique"}
```

A unique, literal string with no regex metacharacters, positive area, fully on-screen.

## Captured evidence (2026-08-05)

Four runs, together showing the verdict came from device-core and not from a trivially-green flow.

### 1. Positive — passes, decided by device-core

```
[Passed] flow (730ms)
1/1 Flow Passed in 734ms
```

Router provenance (`~/.maestro/tests/2026-08-05_125411/maestro.log`, line 65):

```
12:54:17.271 [ INFO] maestro.orchestra.devicecore.DeviceCoreAssertRouter.evaluate:
  device-core decided assert: text='Order summary' match=EXACT mode=VISIBLE
  -> resolution=Resolved(channel=TEXT) boundsSource=MEASURED
     bounds=Rect(x=122, y=160, width=148, height=26) screen=393x852pts verdict=true
```

The MEASURED bounds match the fixture's `TRUTH` exactly. `Resolved(channel=TEXT)` is device-core's
own resolution type — legacy never emits it. A `java`↔8792 socket was live at the same instant
(`lsof -a -c java -iTCP:8792`), so the CLI's device-core client really talked to the driver server.

### 2. Two XCUITest sessions on one simulator

Captured in the same routed run — device-core's runner and legacy's runner both alive at once:

```
=== BOTH XCUITest runners live at 12:54:16 ===
device-core runner PID: 34301 ; legacy runner PID: 73591 ; legacy dynamic port: 61418
--- testmanagerd ---
12:54:16.554  testmanagerd: Received new test session connection from process with PID 73591
12:54:16.556  testmanagerd: Session summary: 2 test sessions, ... has control sessions
```

`Session summary: 2 test sessions`, with legacy's runner joining while device-core's is already
live, is the proof both resolvers can share one simulator. (Legacy uses a *dynamic* host port —
61418 here, not the fixed 22087 an old watcher assumed.)

### 3. Toggle off — passes via legacy, no device-core traffic

```
$ MAESTRO_DEVICECORE_ASSERT=0 maestro test ... flow.yaml --device <UDID>
[Passed] flow (787ms)
1/1 Flow Passed in 790ms
```

- `grep -c "device-core decided" ~/.maestro/tests/2026-08-05_125448/maestro.log` -> `0`
- `lsof -a -c java -iTCP:8792` during the run -> **nothing**

Same flow, same staged screen, still green — but no 8792 socket and no router log. So the `=1`
run's verdict came from device-core, not from the flow being trivially green.

### 4. Negative — routing on, device-core decides the fail

`flow-negative.yaml` asserts a string that's provably absent on the same staged screen:

```
$ MAESTRO_DEVICECORE_ASSERT=1 maestro test ... flow-negative.yaml --device <UDID>
[Failed] flow-negative (1s) (Assertion is false: "This Text Is Absent 9F3K2Q" is visible)
1/1 Flow Failed   (exit code 1)
```

Router log (`~/.maestro/tests/2026-08-05_125521/maestro.log`, lines 65-66):

```
12:55:27.066 [ INFO] ...DeviceCoreAssertRouter.evaluate: device-core decided assert:
  text='This Text Is Absent 9F3K2Q' match=EXACT mode=VISIBLE
  -> resolution=Absent(searched=WHOLE_SCREEN) boundsSource=UNAVAILABLE bounds=null
     screen=393x852pts verdict=false
```

device-core returned `Absent` -> `verdict=false` -> the flow failed at exactly the assert. So the
green in the positive run was never a pass-through. `Absent(searched=WHOLE_SCREEN)` is device-core's
own not-found resolution, distinct from `Unavailable` (which would have thrown an infra error rather
than a false verdict).

## Note on the provenance log

The `device-core decided assert: ...` line comes from one `logger.info` in
`maestro-orchestra/src/main/java/maestro/orchestra/devicecore/DeviceCoreAssertRouter.kt`, added to
report `text / match / mode / resolution / boundsSource / bounds / screen / verdict` after
`inspect()`. It's logging only — no behavior change.
