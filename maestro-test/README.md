# Driver Conformance Harness

A command-line harness that **behavior-tests every `AndroidDriver` command** (`tap`, `swipe`,
`inputText`, `contentDescriptor`, `launchApp`, …) across Android API levels — proving Maestro's
*driver* actually does what it claims on each platform.

> This module (`maestro-test`) also holds shared test doubles (`FakeDriver`, etc.) used by other
> modules' unit tests. The harness below is the headline tool; it lives in `src/main` under the
> `maestro.conformance` package and is run only via the `driverConformance` Gradle task.

## What it is (and what it is *not*)

|  | E2E (Maestro flows) | Driver Conformance (this) |
|---|---|---|
| Unit under test | a user's app + a journey | a single driver command |
| Question | "does *this app's* login work?" | "does `swipe` actually swipe on API 28?" |
| App role | the thing being tested | a controlled **fixture** with known targets |
| Oracle | visible assertions in the flow | an out-of-band event the fixture emits |

**One-liner:** E2E tests *apps* with Maestro; this tests *Maestro's driver* against fixtures. If
`tap` silently breaks on a given API, no E2E flow owns catching it — this harness does.

## What it tests against

Not real apps — a **fixture app** (`conformance-fixtures/native/`) with deterministic targets
(`tap_target`, `swipe_surface`, `text_field`, …). Each command runs like this:

1. **arrange** — `launchApp` deep-links the fixture to the command's screen.
2. **baseline** — capture a watermark (a `MARK` the fixture emits, or a pre-read value).
3. **act** — call the driver command (e.g. `driver.tap(point)`).
4. **verify** — assert the fixture emitted the expected event **with the right payload**.

The oracle is **out-of-band**: the fixture reports what it *actually received* (raw coords, typed
text, swipe direction/distance) over `adb logcat` (tag `MAESTRO_FIXTURE`) — a channel the driver
never touches. So a driver bug can't fake a pass. Each event carries an `(epoch, seq)` so an action
is attributed only to the command that caused it.

## How to run

```bash
# Provision a fresh AVD per API (stock SDK, arm64-v8a), run all commands, generate the report:
./gradlew :maestro-test:driverConformance --args="--api 31 --framework native --record on-failure"

# A range/list of APIs:
./gradlew :maestro-test:driverConformance --args="--api 24..36 --framework native"

# A subset of commands, against an already-running device (BYO, skips provisioning):
./gradlew :maestro-test:driverConformance --args="--api 34 --command tap,swipe --device emulator-5554"

# Regenerate the HTML report from existing results without re-running anything:
./gradlew :maestro-test:driverConformance --args="--report-only"
```

**Flags:** `--api` (single / list `25,26` / range `24..36`), `--framework` (native), `--command`
(default: all Tier-A), `--device <serial>` (BYO, else provisions a fresh AVD), `--record
all|on-failure|never` (per-command screen video; default on-failure), `--out <dir>`,
`--report-only`.

The fixture APK is a **build artifact** (gitignored) — this task builds it on demand; nothing to
commit.

## What you get

A self-contained dark **HTML matrix report** (rows = command, columns = API) at:

```
maestro-test/build/conformance/report/index.html      # gitignored build output
```

Click any cell to drill in: the oracle's **expected vs. actual**, args, timings, and — on
failures (or `--record all`) — an embedded **screen recording**, **screenshot**, and **logcat
slice**. A provisioning-failed API renders as `⚠ FAILED` with its captured exception/stacktrace.
The header lists the **devices tested** (image, abi, device profile, Android version). The index is
built by scanning the on-disk results, so separate/append runs accumulate into one report.

## Layout

```
maestro-test/
├── README.md                                   ← this file
├── src/main/kotlin/maestro/conformance/         ← the harness (CLI, runner, device providers,
│   ├── cli/ runner/ device/ logcat/ behavior/ report/   behaviors, logcat reader, reporter)
│   └── ...
├── src/main/resources/native-fixture.apk        ← bundled fixture APK (gitignored, built on demand)
└── conformance-fixtures/native/                 ← the fixture Android app (:maestro-test:conformance-fixtures:native)
```

## Provisioning

`FreshAvdProvider` creates a fresh AVD via stock SDK tools — **one device at a time**, arm64-v8a
only, single fixed port, userdata capped to 2 GB. It boots, waits for true install-readiness,
pins GBoard, runs, then tears down (reaping the qemu grandchild to avoid port leaks). `--device`
bypasses all of this and runs against a serial you already have.

## Known quirks & platform notes

- **arm64-v8a only.** `detectHostAbi()` targets Apple Silicon; no x86_64 fallback. Use `--device`
  on non-ARM hosts.
- **API 36 image variant.** API ≥ 36 uses the `google_apis_ps16k` system-image package (16 KB
  page size); ≤ 35 uses `google_apis`.
- **GBoard / keyboard commands.** `google_apis` images don't always ship GBoard. When it's absent,
  `inputText` / `eraseText` / `isKeyboardVisible` / `hideKeyboard` / `pressKey` may reflect the
  missing IME rather than a driver bug — a legitimate environment gap, not a regression.
- **API 29 capture.** Some API 29 arm64 images have intermittent `screencap` / `screenrecord`
  failures under HW accel on Apple Silicon; a red `takeScreenshot` there is an artifact gap, not a
  driver regression.
- **Cross-API findings to date.** `killApp` is ineffective on API 24–27 (`am kill` doesn't reap a
  foreground process); APIs 28–34 are clean across all 22 commands. The full 24–36 sweep needs
  ~several GB of image downloads and is intended as a **sharded CI matrix** (one runner per API),
  not a single interactive run.
