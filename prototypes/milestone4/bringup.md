# Milestone 4 — live proof: one real `assertVisible`, decided by maestro-device-core

This is the runbook and captured evidence for the milestone-4 acceptance: a real
`assertVisible` inside a `maestro test` flow, on a booted iOS simulator, decided by
maestro-device-core's `inspect()` while legacy Maestro's own XCUITest runner is co-resident.

Everything below is real captured output from the run on 2026-08-05.

## Environment actually used

| thing | value |
|---|---|
| Simulator | iPhone 14 Pro, iOS 16.4, UDID `6921573F-D8AB-4AC7-A24C-BC700CD7345D` |
| Xcode | 26.6 |
| device-core driver server (8792) | `ConformanceDriverServer.xctestrun`, runner PID 34301 |
| legacy XCUITest runner | `maestro-driver-iosUITests-Runner`, dynamic host port (60995 / 61418 / 60135 across runs) |
| fixture app under test | `dev.mobile.devicecore.conformance.uikit` (ConformanceUIKit) |
| fixture control channel | 8795 (`SCENARIO`/`TRUTH`) |
| seam-carrying CLI | `maestro-cli/build/install/maestro/bin/maestro` (built from this worktree) |

## Chosen target text and its bounds

`<TARGET_TEXT>` = **`Order summary`**

Discovered by staging the fixture's `static-text-unique` cell over the 8795 control
channel and reading `TRUTH`:

```
{"roles":[{"cls":"UILabel","name":"text","rect":{"h":26.33,"w":148.33,"x":122.33,"y":160}}],
 "screen":{"h":852,"scale":3,"w":393},"settled":true,"stem":"static-text-unique"}
```

So the label sits at `Rect(x=122, y=160, w=148, h=26)` points on a `393x852` pt screen —
positive area, fully on-screen. It is a unique, literal string with no regex metacharacters,
so `DeviceCoreRouting.route()` accepts it and device-core resolves it on-screen.

### Why staging + navigation was needed (a real deviation, documented)

The fixture's **default (unstaged) screen is blank** — it renders nothing until a
`SCENARIO <stem>` command arrives on 8795, and staging is in-process (it "MUST NOT require a
relaunch", per `conformance/CONTROL-PROTOCOL.md`). A plain `maestro test` launch shows the
blank screen, so there is no default text to assert on.

Two consequences shape the flow:

1. **The scenario is staged out-of-band before the flow runs.** `bringup` sends
   `SCENARIO static-text-unique` to 8795, which paints "Order summary".
2. **`launchApp` must not relaunch the fixture**, or it would wipe the in-process staging.
   The flow uses `launchApp: { stopApp: false }`. Maestro's iOS `launch` calls
   `simctl launch` **without** `--terminate-running-process`, so with `stopApp: false` it
   foregrounds the already-staged app and the "Order summary" screen survives to the assert.

## Bring-up commands (exact)

```bash
# 1. Boot the sim
xcrun simctl boot 6921573F-D8AB-4AC7-A24C-BC700CD7345D

# 2. Start device-core's driver server on 8792 (a UI-test bundle; parks on the run loop).
#    Reproduces conformance/runner/session.py :: start_driver_server.
xcodebuild test-without-building \
  -xctestrun /Users/stevieclifton/codes/worktrees/maestro-device-core/coresidence-proof/conformance/apps/ios-uikit/build/Build/Products/ConformanceDriverServer.xctestrun \
  -destination id=6921573F-D8AB-4AC7-A24C-BC700CD7345D &
until nc -z 127.0.0.1 8792; do sleep 1; done

# 3. Install + launch the fixture, then stage the screen out-of-band
xcrun simctl install 6921573F-D8AB-4AC7-A24C-BC700CD7345D \
  /Users/stevieclifton/codes/worktrees/maestro-device-core/coresidence-proof/conformance/apps/ios-uikit/build/Build/Products/Debug-iphonesimulator/ConformanceUIKit.app
xcrun simctl launch 6921573F-D8AB-4AC7-A24C-BC700CD7345D dev.mobile.devicecore.conformance.uikit
until nc -z 127.0.0.1 8795; do sleep 1; done
printf 'SCENARIO static-text-unique\n' | nc -w4 127.0.0.1 8795   # -> OK  (paints "Order summary")

# 4. Build the seam-carrying CLI (the mcp-viewer npm task is broken on this host's Node 20.9
#    — pre-existing, unrelated — so exclude it):
./gradlew :maestro-cli:installDist -x buildMcpViewer
```

## IMPORTANT — which `maestro test` invocation actually routes

The device-core seam (Task 5) is wired **only into `TestSuiteInteractor`**. But a *single*
flow file with no report goes through `TestRunner.runSingle`, which has **no** router
(`TestCommand.kt` dispatches `runSingleFlow` unless `isMultipleFiles || isAskingForReport ||
isReplicatingSingleFile`). So the brief's bare `maestro test flow.yaml` runs on **legacy** and
never touches device-core (verified: it passed, but with no `java<->8792` socket and no router
log). To reach the wired path with one flow, pass a report format so `isAskingForReport` is
true:

```bash
export MAESTRO_DEVICECORE_ASSERT=1
export DEVICECORE_IOS_BUNDLE_ID=dev.mobile.devicecore.conformance.uikit   # router also sets this sys prop from appId
maestro test --format JUNIT --output report.xml prototypes/milestone4/flow.yaml \
  --device 6921573F-D8AB-4AC7-A24C-BC700CD7345D
```

(See "Concerns" — wiring `runSingle` too is a one-spot follow-up so the plain command routes.)

---

## Evidence

### 1. Positive run — PASSES, decided by device-core

```
$ MAESTRO_DEVICECORE_ASSERT=1 DEVICECORE_IOS_BUNDLE_ID=...uikit \
    maestro test --format JUNIT --output report-positive.xml prototypes/milestone4/flow.yaml --device 6921573F-...
Waiting for flows to complete...
[Passed] flow (730ms)

1/1 Flow Passed in 734ms
```

**Router provenance log** (`~/.maestro/tests/2026-08-05_125411/maestro.log`, line 65):

```
12:54:17.271 [ INFO] maestro.orchestra.devicecore.DeviceCoreAssertRouter.evaluate:
  device-core decided assert: text='Order summary' match=EXACT mode=VISIBLE
  -> resolution=Resolved(channel=TEXT) boundsSource=MEASURED
     bounds=Rect(x=122, y=160, width=148, height=26) screen=393x852pts verdict=true
```

The MEASURED bounds match the fixture's `TRUTH` exactly. `Resolved(channel=TEXT)` and
`searched=...` are device-core's own resolution types — legacy never emits them.

**java <-> 8792 socket during the assert** (the CLI JVM's device-core client;
`lsof -a -c java -iTCP:8792`, same instant as the router log):

```
=== java<->8792 @ 12:54:17.266 ===
java  71458 stevieclifton  217u  IPv6 ...  TCP 127.0.0.1:61559->127.0.0.1:8792 (ESTABLISHED)
```

### 2. Co-residence — both XCUITest runners live, testmanagerd "2 test sessions"

Captured in the **same** routed run (both runner PROCESSES alive at once; note the legacy
runner uses a *dynamic* host port — 61418 here — not the fixed 22087 the old rig watcher
assumed, which is why a 22087-keyed watcher reports nothing):

```
=== BOTH XCUITest runners live at 12:54:16 ===
device-core runner PID: 34301 ; legacy runner PID: 73591 ; legacy dynamic port: 61418
34301 .../ConformanceDriverServer-Runner.app/ConformanceDriverServer-Runner
73591 .../maestro-driver-iosUITests-Runner.app/maestro-driver-iosUITests-Runner
--- lsof :8792 (device-core) ---
Conforman 34301 ... TCP 127.0.0.1:8792 (LISTEN)
--- device-core 8792 serving (garbage line -> ok:false) ---
{"error":"unparseable request line","ok":false,"payload":null}
--- testmanagerd ---
12:54:16.554  testmanagerd: Received new test session connection from process with PID 73591
12:54:16.556  testmanagerd: Session summary: 2 test sessions, ... has control sessions
12:54:16.584  testmanagerd: Authorizing process with pid 73591
```

`Session summary: 2 test sessions` with legacy's runner (73591) joining while device-core's
session (34301) is already live is the definitive co-residence proof: two XCUITest sessions
on one simulator at the serving instant.

### 3. Toggle control — routing OFF passes via legacy, no device-core traffic

```
$ MAESTRO_DEVICECORE_ASSERT=0 maestro test --format JUNIT --output report-toggle.xml prototypes/milestone4/flow.yaml --device 6921573F-...
[Passed] flow (787ms)
1/1 Flow Passed in 790ms
```

- `grep -c "device-core decided" ~/.maestro/tests/2026-08-05_125448/maestro.log` -> `0`
- `lsof -a -c java -iTCP:8792` during the run -> **nothing**

Same flow, same staged screen, still green — but no 8792 socket and no router log. This
isolates that the `=1` run's verdict came from device-core, not from the flow being trivially
green.

### 4. Negative control — routing ON fails, and device-core decided the fail

```
$ MAESTRO_DEVICECORE_ASSERT=1 maestro test --format JUNIT --output report-negative.xml prototypes/milestone4/flow-negative.yaml --device 6921573F-...
[Failed] flow-negative (1s) (Assertion is false: "This Text Is Absent 9F3K2Q" is visible)
1/1 Flow Failed   (exit code 1)
```

Router log (`~/.maestro/tests/2026-08-05_125521/maestro.log`, lines 65-66):

```
12:55:27.066 [ INFO] ...DeviceCoreAssertRouter.evaluate: device-core decided assert:
  text='This Text Is Absent 9F3K2Q' match=EXACT mode=VISIBLE
  -> resolution=Absent(searched=WHOLE_SCREEN) boundsSource=UNAVAILABLE bounds=null
     screen=393x852pts verdict=false
12:55:27.233 [ERROR] maestro.orchestra.Orchestra.executeCommands:
  CommandFailed: Assertion is false: "This Text Is Absent 9F3K2Q" is visible
```

device-core returned `Absent` -> `verdict=false` -> the flow failed at exactly the assert, so
the green in the positive run was never a pass-through. `Absent(searched=WHOLE_SCREEN)` is
device-core's own not-found resolution, distinct from `Unavailable` (which would have thrown
`DeviceCoreUnavailable` — an infra failure, not a verdict).

## Production code change made for this proof

Added one `logger.info` in
`maestro-orchestra/src/main/java/maestro/orchestra/devicecore/DeviceCoreAssertRouter.kt`
reporting `text / match / mode / resolution / boundsSource / bounds / screen / verdict` after
`inspect()`. This is the router provenance line quoted above. No behavior change.
