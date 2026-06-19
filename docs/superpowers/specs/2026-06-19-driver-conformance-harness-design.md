# Driver Conformance Harness — Design

**Status:** Draft for review
**Date:** 2026-06-19
**Scope:** Android-only (v1), abstraction-ready for iOS

---

## 1. Purpose & Positioning (why this exists, and why it is NOT E2E)

The **Driver Conformance Harness** proves that **each `AndroidDriver` primitive behaves
correctly across the rendering frameworks and API levels Maestro supports.**

It sits directly on top of `maestro-client`'s `AndroidDriver` (the `Driver` interface) and
exercises one command at a time against controlled fixture apps, verifying the *effect* of
the command via a channel independent of the driver.

### How it differs from E2E

| | E2E (Maestro flows) | Driver Conformance (this) |
|---|---|---|
| Unit under test | A user's app + a journey through it | A single driver command (`tap`, `swipe`, …) |
| Question answered | "Does *this app's* login flow work?" | "Does `swipe` actually swipe on Compose API 28? Flutter API 35?" |
| App role | The thing being tested | A controlled *instrument* (fixture) with known targets |
| Oracle | Visible UI assertions in the flow | Out-of-band event the fixture emits, independent of the command |
| Axis of variation | App features | framework × API level × command |
| Who it protects | The app author | **Maestro itself** — catches driver regressions before every user hits them |

**One-liner for the team:** *E2E tests apps with Maestro; the conformance harness tests
Maestro's driver against apps.* If `tap` silently breaks on Flutter API 35, no single E2E
flow is responsible for catching it — this harness is.

---

## 2. Key Decisions (locked)

1. **Platform scope:** Android-only for v1; design abstractions so `IOSDriver` (SwiftUI/UIKit)
   drops in later without reworking the harness core.
2. **Oracle:** Out-of-band app oracle. Fixtures emit structured events via a channel
   independent of the driver (logcat), so observation never depends on the command under test.
3. **Provisioning:** No dependency on `maestro-device`. Fresh AVDs created with stock Android
   SDK tooling, behind a swappable `DeviceProvider` interface. Explicit BYO-device allowed
   (with a loud banner); never silently adopt a running emulator for the matrix.
4. **Selection:** Cross product of `--api` × `--framework` → independent cells.
5. **Fixtures:** One shared *fixture contract*; one thin app per framework implementing it.
   Harness logic is framework-blind.
6. **Command scope:** Tier A (UI-interaction commands) for v1; structure so Tier B
   (device-state) and Tier C (meta) plug in later.
7. **Artifacts:** `command.json` (the verdict/evidence record) is the spine; media (video,
   stills, hierarchy, logcat) are evidence captured in tiers proportional to cost vs. use.
8. **No new module.** The harness lives **inside `maestro-client`** in a dedicated
   `conformance` source set with its own runnable entrypoint + Gradle task — it reuses
   `AndroidDriver` directly and avoids spinning up a new Gradle module.
9. **Isolated from unit tests.** The conformance task is **not** wired into `test` / `check`,
   so `./gradlew test` and the unit-test CI (`test.yaml`) never run it. It needs a live device
   and is far slower than a unit test.
10. **On-demand trigger (v1).** Runs via a manual `workflow_dispatch` GitHub Actions workflow,
    separate from unit-test CI. Future: target by file changes (driver / fixture paths).

---

## 3. Architecture

Lives **inside `maestro-client`** in a dedicated `conformance` source set (e.g.
`maestro-client/src/conformance/kotlin`), reusing `AndroidDriver` directly — no new Gradle
module. Built and run via a dedicated Gradle task that is excluded from `test` / `check`
(see §9). Five decoupled pieces:

```
┌─────────────────────────────────────────────────────────────┐
│  CLI / entrypoint  (--api, --framework, selection → cells)   │
└───────────────┬─────────────────────────────────────────────┘
                │ for each cell (api × framework):
        ┌───────▼────────┐   ┌──────────────┐   ┌────────────────┐
        │ DeviceProvider │   │ FixtureApp    │   │ CommandBehavior│
        │ (fresh AVD,    │──▶│ (per-framework│◀─│  registry      │
        │  swappable)    │   │  contract)    │   │ (Tier A cmds)  │
        └───────┬────────┘   └──────┬────────┘   └──────┬─────────┘
                │ DeviceHandle       │ installs          │ runs
                │ (serial+Driver)    ▼                   ▼
                │            ┌─────────────────────────────────┐
                │            │  EventOracle (logcat reader)     │
                │            └────────────────┬────────────────┘
                ▼                             ▼
        ┌──────────────────────────────────────────────────────┐
        │  Reporter → per-cell artifacts + JSON + HTML aggregate │
        └──────────────────────────────────────────────────────┘
```

- **DeviceProvider** — `FreshAvdProvider` (default), `AttachedDeviceProvider` (explicit BYO),
  future `GoldenSnapshotProvider` / `CloudDeviceProvider`. Hands back `DeviceHandle`
  (serial + booted `AndroidDriver`).
- **FixtureApp** — one per framework, all satisfying the same contract (§5).
- **CommandBehavior** — one small class per command encoding its before/after test (§4).
- **EventOracle** — reads the out-of-band logcat event stream, decodes the structured protocol.
- **Reporter** — per-cell artifact dir + machine-readable JSON + static HTML aggregator (§8).

The harness core only ever sees `DeviceHandle` — it does not know or care how the device was
born, which is what keeps it host-independent.

---

## 4. Per-command test model (red/green, adapted)

maestro-device's red/green proves *causation*. For a command, causation = the observable
effect appears only because the command ran. Each `CommandBehavior` has four steps:

```
1. arrange   → driver.launchApp + navigate fixture to the command's screen
2. pre-check → assert oracle shows NO event yet         (the "red" baseline)
3. act       → driver.<command>(args)                   (the thing under test)
4. post-check→ assert oracle emitted exactly the expected event (the "green")
```

Example — `tap`:
1. open fixture `TapScreen` (target at known element id `tap_target`).
2. assert no `TAP` event in the oracle stream.
3. `driver.tap(centerOf("tap_target"))`.
4. assert oracle emitted `TAP target=tap_target x=… y=…` within tolerance.

`swipe` asserts direction + distance; `inputText` asserts the received string;
`setOrientation` asserts the fixture's reported orientation flipped; etc. An optional
**negative control** per command (e.g. tap empty space → no event) strengthens the proof
where cheap.

### Tier A commands (v1)
`tap`, `longPress`, `swipe` ×3, `inputText`, `eraseText`, `pressKey`, `backPress`,
`scrollVertical`, `contentDescriptor`, `queryOnDeviceElements`, `isKeyboardVisible`,
`hideKeyboard`, `launchApp`/`stopApp`/`killApp`/`clearAppState`, `setOrientation`,
`takeScreenshot`, `openLink`, `waitUntilScreenIsStatic`, `waitForAppToSettle`.

### Deferred (designed-for, not built in v1)
- **Tier B (device-state, system-probe oracles):** `setLocation`, `setPermissions`,
  `addMedia`, `setAirplaneMode`/`isAirplaneModeEnabled`, `setProxy`/`resetProxy`,
  `clearKeychain`, `setAndroidChromeDevToolsEnabled`.
- **Tier C (meta/hard-to-assert):** `deviceInfo`, `name`, `capabilities`, `open`/`close`,
  `isShutdown`, `isUnicodeInputSupported`, `startScreenRecording`.

---

## 5. Fixture Contract

A single spec every framework app implements, so the harness is framework-blind.

- **Screens:** one per command-group (`TapScreen`, `SwipeScreen`, `InputScreen`,
  `KeyboardScreen`, `ScrollScreen`, `OrientationScreen`, `AppLifecycleScreen`, …), reachable
  by a stable route.
- **Element IDs:** identical, stable identifiers (`tap_target`, `swipe_surface`,
  `text_field`) exposed via each framework's accessibility/testID mechanism so
  `contentDescriptor` / `queryOnDeviceElements` see them consistently.
- **Event protocol (out-of-band channel):** every app logs one structured line per observed
  interaction to logcat under a fixed tag, with a monotonic `seq` to defeat races:
  ```
  MAESTRO_FIXTURE {"seq":12,"event":"SWIPE","dir":"UP","dx":2,"dy":-540,"target":"swipe_surface"}
  ```
  Channel per framework: Native/Compose → `Log.d`; Flutter → `debugPrint`; RN →
  `console.log`; WebView → `console.log` (surfaces in chromium logcat). The oracle filters by
  tag + seq.
- **Conformance self-test:** each fixture must emit for a known synthetic action at startup,
  so a *broken fixture* fails loudly rather than masquerading as a driver bug.

Adding a 7th framework (or iOS later) = implement this contract. Nothing else changes.

### Frameworks (v1, Android)
Native Android, Jetpack Compose, React Native, Flutter, WebView-based.
(iOS SwiftUI / UIKit deferred behind the same contract + `IOSDriver`.)

---

## 6. Provisioning & "ensure env" (host-independent)

Hard split between *what runs the tests* and *what gives me a device*, connected only by an
adb serial. Harness core asks the world for one thing: a serial reachable over adb.

- **Preflight (fail fast, actionable):** adb present, JDK ok, SDK + required system images
  installed (offer `sdkmanager` install), hardware accel available (KVM/HVF), disk space.
- **`FreshAvdProvider`:**
  - `avdmanager create avd -n maestro-conformance-api{N} -k "system-images;android-{N};google_apis;<abi>" --device pixel_6 --force`
  - boot: `emulator -avd … -no-snapshot-save -no-window -no-boot-anim -no-audio -no-metrics`
  - wait-for-ready: `adb wait-for-device` → `getprop sys.boot_completed == 1` →
    `settings list global` and `pm get-max-users` exit 0
  - on release: wipe/delete the AVD.
  - Clean state comes from a *freshly created* AVD (pristine userdata) — no golden snapshot
    needed. This is the deliberate trade vs. maestro-device: we give up warm-boot speed to
    avoid the device-side configurator/snapshot dependency.
- **Explicit BYO:** `--device <serial>` / `ANDROID_SERIAL` runs there and prints a loud
  banner in console + report header:
  `⚠ user-supplied device <serial> — state not managed by harness`.
  The matrix never auto-adopts a random running emulator.
- **Host-independence:** stock Android SDK only → identical on laptop, CI, anyone's machine.
  Same single entrypoint everywhere.

### DeviceProvider interface

```kotlin
interface DeviceProvider {
    fun acquire(spec: DeviceSpec): DeviceHandle   // serial + booted AndroidDriver, ready
    fun release(handle: DeviceHandle)             // teardown / wipe / return to pool
}
```

---

## 7. Matrix selection & CLI

Invoked via a Gradle task on the `conformance` source set (not a standalone module binary):

```
./gradlew :maestro-client:driverConformance \
  --api 25,26,27,28,29,30,31 --framework flutter,react-native \
  [--command tap,swipe]          # default: all Tier A
  [--device <serial>]            # BYO override, skips provisioning
  [--record all|on-failure|never]
  [--out ./report]
```

- The task wraps a runnable entrypoint (Clikt-style arg parsing) — **not** JUnit, so the
  suite is never swept up by `./gradlew test`.
- `--api` accepts lists and ranges (`24..36`); `--framework all`; cross product → **cells**.
- Each cell = (api, framework); within it, every selected command's behavior test runs.
- Cells are independent → parallelizable later; v1 may run sequentially per device.
- Supported API range: **24–36**.

---

## 8. Reporting & Artifacts

Mirrors maestro-device's shape (per-run dirs + JSON + HTML aggregator + `file://`-openable
`.js` mirror), with **command** in place of red/green side and **cell (api×framework)** in
place of (step×api).

### Artifact tree

```
report/
├── index.html                 # aggregator viewer: grid rows=command, cols=api×framework
├── summary.json               # machine-readable roll-up: totals, per-cell status, env banner, durations
├── summary.js                 # report.js-style mirror so index.html opens over file://
└── cells/
    └── api34-compose/                    # one dir per cell (api × framework)
        ├── cell.json                     # per-command pass/fail rollup for the cell
        ├── env.log                       # serial, api, abi, device profile, accel, fixture build id, BYO banner
        ├── fixture-install.log           # apk/bundle install output
        ├── maestro.log                   # driver-level log for the whole cell
        └── tap/                          # one dir per command (the "unit")
            ├── command.json              # THE evidence record (see below)
            ├── events.log                # raw out-of-band oracle stream (always)
            ├── after.png                 # end-state still (always)
            ├── recording.mp4             # gesture replay (failure bundle / --record all)
            ├── before.png                # paired diff still (failure bundle / --record all)
            ├── hierarchy.json            # contentDescriptor dump (failure bundle / --record all)
            └── logcat-slice.txt          # raw unfiltered logcat for the test window (failure bundle)
```

### `command.json` — the spine

The one artifact that turns raw evidence into a machine-readable verdict. Drives the HTML grid
and the CI exit code; enables cross-matrix diffing and reproduction.

```json
{
  "command": "swipe",
  "args": { "start": [540,1600], "end": [540,400], "durationMs": 300 },
  "target": { "id": "swipe_surface", "resolvedBounds": [40,300,1040,1600] },
  "expected": { "event": "SWIPE", "dir": "UP", "dyTolerance": 50 },
  "actual":   { "event": "SWIPE", "dir": "UP", "dy": -1180 },
  "verdict": "PASS",
  "failureReason": null,
  "timings": { "actMs": 312, "totalMs": 940 },
  "artifacts": ["events.log", "after.png"]
}
```

### Artifact policy (tiered by cost vs. use)

- **Always (cheap, every run):** `command.json`, `events.log`, `after.png`.
- **Failure bundle (on fail, or promoted by `--record all`):** `recording.mp4`, `before.png`,
  `hierarchy.json`, `logcat-slice.txt`.
- **`--record on-failure`** is the lean CI default; **`--record all`** keeps the full bundle
  for green runs too (full green+red parity); **`--record never`** disables media.
- Rationale: never capture video + hierarchy for every passing command — it triples disk and
  wall-clock on the 95% case nobody opens, and would make a 13-API × 6-framework matrix crawl.

### Screen recording specifics

- Per-command short clips via `adb shell screenrecord` around the `act` step (start → before
  → act → after → SIGINT to flush → pull). Per-command (not one-per-cell) because
  `screenrecord` has a **180s cap on API 29–32**; a full cell could exceed it, and the only
  way maestro-device dodges this is by patching the device-side binary — a dependency we
  reject for host-independence.
- **Recording never fails a test** — it is a diagnostic artifact; the verdict rests on the
  out-of-band oracle. On recording error the artifact is marked unavailable.
- **Known caveats handled gracefully:** API 29 on Apple-Silicon emulators can emit
  0-byte/broken output (detected → marked unavailable); high-DPI AVDs (pixel_6 long edge
  2400 > 1920 AVC cap) get `--size` capped ≤1920 long-edge so clips aren't silently squashed
  (recording arg only, no binary patch).

---

## 9. Execution model & CI

### Where it lives & how it's wired
- Code: `maestro-client/src/conformance/kotlin` (dedicated `conformance` source set), reusing
  `maestro-client`'s `main` (so `AndroidDriver` is on the classpath without a new module).
- Gradle: a `driverConformance` task with `JavaExec`-style execution of the runnable
  entrypoint. **It is deliberately NOT a dependency of `test`, `check`, or `build`** — running
  unit tests must never trigger a device-backed conformance run.
- A separate `conformanceCompile`/source-set check may compile the code, but execution is
  always explicit via the task.

### Isolation from unit-test CI
- The existing unit-test workflow (`test.yaml`) runs `./gradlew test` and **must not** pick up
  conformance. Because conformance is its own source set + task outside `check`, this holds by
  construction.
- Conformance has its own GitHub Actions workflow, e.g. `.github/workflows/driver-conformance.yaml`.

### Trigger (v1 = on-demand)
- `workflow_dispatch` only, mirroring `test-e2e.yaml`. Manual inputs map to the CLI flags:
  `api` (e.g. `34` or `24..36`), `framework` (e.g. `flutter,compose` or `all`),
  `command` (optional), `record` (`all|on-failure|never`).
- Runs on a runner with the Android SDK + emulator acceleration; provisions fresh AVDs via the
  `FreshAvdProvider` (§6).
- **Future (not v1):** add `pull_request` / `push` path filters so changes under the driver or
  fixture paths (e.g. `maestro-client/src/.../drivers/AndroidDriver.kt`,
  `maestro-android/**`, fixture dirs) auto-trigger a targeted subset.

### Artifacts in CI
- The `report/` tree (§8) is uploaded as a workflow artifact; `index.html` is the entry point
  for "what passed / what didn't," each red cell drilling into per-command evidence.

## 10. Phasing

1. **Skeleton + 1 framework + 3 commands** (native Android; `tap`, `inputText`, `swipe`) on
   one API → proves DeviceProvider + EventOracle + Reporter end-to-end.
2. **All Tier A commands** on native Android, single API.
3. **Add fixtures**: Compose → React Native → Flutter → WebView, each satisfying the contract.
4. **Matrix out** to API 24–36; HTML aggregator.
5. **Design-in hooks** for Tier B (system-probe oracles) and iOS (`IOSDriver` behind the same
   `DeviceProvider` / contract).

---

## 11. Resolved & open questions

### Resolved
- **Placement:** inside `maestro-client` (`conformance` source set), no new module.
- **Entrypoint:** runnable Clikt-style CLI via a Gradle task — not JUnit — so it stays out of
  `./gradlew test`.
- **CI trigger:** on-demand `workflow_dispatch` in its own workflow, isolated from unit-test CI.

### Still open
- **Parallelism in v1:** sequential per device first, or build cell-level parallelism in from
  the start?
- **File-change targeting (future):** exact path globs that should auto-trigger a targeted run.
