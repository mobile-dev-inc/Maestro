# AGENTS.md — Maestro

Shared context for any Claude Code skill or subagent operating in this repo. Skills (`.claude/skills/*`) reference this file rather than restating module roles; if a description here drifts from reality, fix it here once and every skill follows.

## Module map

Top-level Gradle modules. Code lives under each module's `src/main/`.

| Module                       | Role                                                                                                                                                                                                                                                                                     |
|------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `maestro-android/`           | On-device Android driver. Kotlin sources compile to two checked-in APKs (`maestro-app.apk`, `maestro-server.apk`) consumed by `maestro-client/`. The build's `copyMaestroAndroid` / `copyMaestroServer` finalizers update those APKs plus a `maestro-android-source.sha256` checksum.    |
| `maestro-ios-driver/`        | On-device iOS driver wrapper (Kotlin). The actual XCTest runner lives in `maestro-ios-xctest-runner/`.                                                                                                                                                                                   |
| `maestro-ios-xctest-runner/` | Swift XCTest runner that runs on the iOS device/simulator. The compiled artifacts (`maestro-driver-ios*.zip`) are checked in under `maestro-ios-driver/src/main/resources/driver-iPhoneSimulator/Debug-iphonesimulator/`.                                                                |
| `maestro-ios/`               | iOS host-side glue (small — most iOS host code lives in `maestro-client/`).                                                                                                                                                                                                              |
| `maestro-client/`            | Host-side Kotlin SDK that drives devices. Platform drivers live in `src/main/java/maestro/drivers/`: `AndroidDriver.kt`, `IOSDriver.kt`, `WebDriver.kt`, `CdpWebDriver.kt`. This is where most "auto-grant", "auto-dismiss", system-dialog handling and platform-specific quirks belong. |
| `maestro-orchestra/`         | Command execution layer. `Orchestra.kt` interprets each Maestro command, applies retries, manages the command lifecycle. Sub-packages: `error/`, `filter/`, `workspace/`, `yaml/`.                                                                                                       |
| `maestro-orchestra-models/`  | Shared command/data models (used by `maestro-orchestra/` and consumers).                                                                                                                                                                                                                 |
| `maestro-cli/`               | CLI entry point + MCP server. Mixed Kotlin (~100 files) + Swift (~56 files for iOS-related CLI bits).                                                                                                                                                                                    |
| `maestro-utils/`             | Shared utilities.                                                                                                                                                                                                                                                                        |
| `maestro-web/`               | Web (browser) driver pieces.                                                                                                                                                                                                                                                             |
| `maestro-proto/`             | Protobuf definitions shared across modules.                                                                                                                                                                                                                                              |
| `maestro-test/`              | Test fixtures used across modules.                                                                                                                                                                                                                                                       |

## E2E test fixtures (`e2e/`)

Shipped fixtures used by `.github/workflows/test-e2e.yaml`. Run via `e2e/run_tests <android|ios|web>` (see `e2e/run_tests` for env-var inputs `MAESTRO_APP`, `MAESTRO_FLOW_PATH`).

| Path                     | Role                                                                                                                                                                    |
|--------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `e2e/demo_app/`          | Flutter demo app whose only purpose is to exercise Maestro features. Contains its own `CLAUDE.md`. Built binaries are uploaded to a GCS bucket and re-downloaded by CI. |
| `e2e/demo_app/.maestro/` | Maestro flow YAMLs that drive the demo app.                                                                                                                             |
| `e2e/workspaces/`        | Additional app workspaces (e.g. `simple_web_view`, `wikipedia`).                                                                                                        |
| `e2e/run_tests`          | Test driver invoked by the workflow.                                                                                                                                    |

### `passing/` vs `failing/` suites

Tag-based filters inside the YAML flows split test runs into two suites at execution time:

- `passing/` — flows tagged `passing`. **Expected to pass.** Any failure here is a real regression. This is the only suite the diagnose agent reads.
- `failing/` — flows tagged `failing`. **Expected to fail** (negative-path coverage: assertions that should not match, commands that should error). The workflow inverts the success check on this suite. Do not treat `failing/` artifacts as regressions.

Artifacts land at `<artifact_root>/tests/<app>/<suite>/`:

- `commands-(<flow>).json` — one entry per command with `metadata.status` ∈ `{COMPLETED, FAILED}`.
- `screenshot-(❌|✅)-<timestamp>-(<flow>).png` — captured per command. Note: `retryCommand` leaves an `❌` screenshot from the failed attempt even when the retry recovers and the flow ends `COMPLETED`. The JSON is the source of truth, not the screenshot filename.
- `maestro.log` — Maestro driver / orchestra logs for the run.

## `test-e2e.yaml` workflow contract

`.github/workflows/test-e2e.yaml` is the validation harness for both PR triggers and manual `workflow_dispatch` (e.g. validating a new Android API level or iOS version). Contract:

- **`workflow_dispatch` inputs** — `android_version` (choice enum), `app` (string, default `demo_app`), `flow` (string, optional single-flow). The `validate-inputs` job rejects `android_version <= android-29`, missing `app` workspace, or ambiguous `flow`. (See PR #3226.)
- **`pull_request` triggers** are byte-identical to the prior behaviour; manual dispatches use the new narrowing knobs.
- **`test-android` job** boots an emulator on `system-images;${android_version};google_apis;x86_64` and runs `e2e/run_tests android`.

Skills that bump platform versions (Android API levels, iOS versions) drive this workflow via `gh workflow run test-e2e.yaml --ref <branch> -f android_version=<...>`.

## Conventions

- Kotlin 1.9 / JVM 17. Gradle. No DI framework — services are constructed manually.
- Protobuf for the on-device wire format (`maestro-proto/`); JSON for artifact debug output.
- Coroutines with explicit dispatchers; `runBlocking` only at entry points.
- Exposed exceptions classify failures (retryable vs terminal) — see `maestro-orchestra/src/main/java/maestro/orchestra/error/`.

## Where Claude Code resources live

- `.claude/skills/*` — skills (workflows). Each skill's `SKILL.md` references this file for module roles.
- `.claude/agents/*.md` — subagents (e.g. `diagnose-maestro-failure.md`). Their input/output contracts are documented in each file.

## What NOT to do

- Don't fix driver-behaviour gaps by patching `.github/workflows/test-e2e.yaml` (e.g. extra `adb shell settings put …`, command-line tweaks, AVD pre-config). Workflow band-aids hide the regression from users running Maestro outside our CI. Fix `maestro-android/`, `maestro-client/`, or `e2e/demo_app/` instead so the fix ships with the driver APKs. Workflow edits are valid for shape-changes (matrix, retention, dispatch inputs) and the narrow third-party-FRE exception documented in skill files.
- Don't edit checked-in driver artifacts (`maestro-app.apk`, `maestro-server.apk`, `maestro-android-source.sha256`, `maestro-driver-ios*.zip`) by hand — they are gradle finalizer outputs.
- Don't modify existing flows in `failing/` to make them pass — that's the negative-path suite by design.
