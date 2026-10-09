# Dynamic sharding (`--shard-split-dynamic`)

Runs a test suite across N devices from a single shared queue. Each device pulls the next flow as soon as it finishes the previous one, so no device sits idle while flows are still pending.

## 1. Difference from `--shard-split`

| | `--shard-split N` | `--shard-split-dynamic N` |
|---|---|---|
| How flows are assigned | Up front, round-robin by index (`flow i → shard i % N`) | On demand, from one shared queue |
| A device finishes early | It stays idle until the slowest shard ends | It takes the next pending flow |
| A device crashes | The rest of its shard is lost | The in-progress flow goes back to the queue and another device runs it |
| Execution order | Deterministic per shard | Not deterministic |

`--shard-split` and `--shard-all` are unchanged. The three options are mutually exclusive.

## 2. When to use which

| Situation | Use |
|---|---|
| Few flows with very different durations (one 10 min flow, many 30 s flows) | `--shard-split-dynamic` |
| Devices that can crash or disconnect during long runs (emulators, device farms) | `--shard-split-dynamic` |
| Many short flows with similar durations on stable devices | Either; the results are close (see Evidence) |
| You need the same flow on the same device every run | `--shard-split` |
| `sequence` flows (`executionOrder`) | `--shard-split` (the dynamic path only runs `flowsToRun`) |

## 3. Flags

| Flag | Default | Meaning |
|---|---|---|
| `--shard-split-dynamic N` | — | Number of workers (devices). Capped at the number of connected devices. |
| `--min-healthy-devices M` | `2` | If fewer than M workers are alive, the run is aborted with a `CliError` instead of letting a single surviving device run the whole queue. |

Example in CI with 4 emulators already booted:

```bash
maestro test \
  --shard-split-dynamic 4 \
  --min-healthy-devices 2 \
  --format JUNIT --output report.xml \
  .maestro/
```

The JUnit report has one `<testsuite>` per worker session, with the device in the `device` attribute, so each flow can be traced to the device that ran it.

## 4. Behaviour and limitations

- **One flow per device at a time.** Parallelism equals the number of workers.
- **Assertion failures are not retried.** A flow that fails (`ERROR`) is reported once and consumed from the queue.
- **Device crashes are retried elsewhere.** If the session dies mid-flow (`DeviceUnreachableException` and similar transport errors), the flow is re-enqueued and the worker stops. Re-running assumes the flow can start from a clean state on another device.
- **Fail-fast.** When alive workers drop below `--min-healthy-devices`, pending flows are not executed and the run ends with an error.
- **No `sequence` flows and no `--continuous` mode** in the dynamic path.
- **Order is not deterministic.** Do not rely on flow A running before flow B.

## 5. How it is implemented

| Step | File | What it does |
|---|---|---|
| 1. Flags | `maestro-cli/.../command/TestCommand.kt` | `--shard-split-dynamic`, `--min-healthy-devices`, mutual exclusion with `--shard-split`/`--shard-all`, dynamic branch in `handleSessions` |
| 2. Worker loop | `maestro-cli/.../runner/TestSuiteInteractor.kt` | `runFromQueue()` consumes one flow at a time and calls `onDeviceCrash` on transport errors; `buildSummary()` shared with `runTestSuite()` |
| 3. Scheduler | `maestro-cli/.../runner/DynamicShardScheduler.kt` | One coroutine per device; `Channel.UNLIMITED` pre-filled with every flow; `AtomicInteger` for pending flows and alive workers; fail-fast via a shared cancellation flag |
| 4. Report | `TestCommand.kt` | Summaries from every worker merged with the existing `mergeSummaries()` / `saveReport()` / `printShardsMessage()` |
| 5. Tests | `DynamicShardSchedulerTest`, `TestSuiteInteractorTest`, `TestCommandTest` | See below |

Design choices:

- **`Channel` instead of locks**: a channel guarantees that two workers never receive the same flow, and re-enqueueing is a plain `trySend`.
- **The channel is never closed**: a crashed worker may return a flow at any time, so workers stop on `pending == 0`, not on an empty channel.
- **`AtomicInteger` counters**: no shared mutable state other than the channel and two counters.

## 6. Testing

Unit tests run without devices. `DynamicShardSchedulerTest` injects a `SessionOpener` backed by `FakeDriver`:

| Scenario | Assertion |
|---|---|
| 9 flows, 3 devices | Every flow runs exactly once |
| One fast and one slow device | The fast device runs more flows |
| One device crashes | Its flow is re-enqueued and run by the healthy device |
| Alive devices < `--min-healthy-devices` | `CliError` |
| Assertion failure | Reported once, not re-enqueued |
| Empty queue | Finishes without errors |

```bash
./gradlew :maestro-cli:test --tests 'maestro.cli.runner.*' --tests 'maestro.cli.command.TestCommandTest'
```

Locally with emulators:

```bash
emulator -avd Pixel_7 -port 5554 -no-snapshot-load &
emulator -avd Pixel_8 -port 5556 -no-snapshot-load &
adb wait-for-device
maestro test --shard-split-dynamic 2 --min-healthy-devices 1 e2e/demo_app/.maestro/
```

Kill one emulator in the middle of the run: its in-progress flow is picked up by the other one, and the run still reports every flow.

## Evidence (production suite, ~150 Android flows)

Measured on a real regression suite of ~150 flows on 4 Android emulators (flow durations from a few seconds to ~6 minutes, median ~2.3 minutes):

| | Result |
|---|---|
| Spread between the first and the last device to finish | 82 seconds after ~86 minutes of run |
| Same flows simulated with `--shard-split 4` (round-robin, real durations) | ~86 minutes, ~5.5 device-minutes idle in total |
| Run where 2 of 3 emulators lost their driver mid-run | The surviving emulator ran ~2/3 of the suite (96 flows) and every flow got a result. With a static split, the ~100 flows assigned to the 2 dead devices would not have run |

The main gain is resilience, not speed. On stable devices with many short flows a round-robin split is already close to balanced. The dynamic queue pays off when devices die or durations are uneven.
