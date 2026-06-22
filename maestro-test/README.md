# Driver Conformance Harness

A command-line harness that **behavior-tests every `Driver` command** (`tap`, `swipe`,
`inputText`, `contentDescriptor`, `launchApp`, …) across different OS levels — proving Maestro's
*driver* actually does what it claims on each platform.


## What it tests against

Not real apps — **fixture apps** (`conformance-fixtures/native/`) with deterministic targets
(`tap_target`, `swipe_surface`, `text_field`, …). Each command runs like this:

1. **arrange** — `launchApp` deep-links the fixture to the command's screen.
2. **baseline** — capture a watermark (a `MARK` the fixture emits, or a pre-read value).
3. **act** — call the driver command (e.g. `driver.tap(point)`).
4. **verify** — assert the fixture emitted the expected event **with the right payload**.

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

## What you get

A self-contained dark **HTML matrix report** (rows = command, columns = API) at:

```
maestro-test/build/conformance/report/index.html      # gitignored build output
```

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

## Provisioning Device

`FreshAvdProvider` creates a fresh AVD via stock SDK tools — **one device at a time**, arm64-v8a
only, single fixed port, userdata capped to 2 GB. It boots, waits for true install-readiness,
pins GBoard, runs, then tears down (reaping the qemu grandchild to avoid port leaks). `--device`
bypasses all of this and runs against a serial you already have.
