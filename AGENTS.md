# AGENTS.md — Maestro

Shared context for any Claude Code skill or subagent operating in this repo. Skills (`.claude/skills/*`) reference this file rather than restating module roles; if a description here drifts from reality, fix it here once and every skill follows.

## Module map

Top-level Gradle modules. Code lives under each module's `src/main/`.

| Module                       | Role                                                                                                                                                                                                                                                                                     |
|------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `maestro-android/`           | On-device Android driver. Kotlin sources compile to two checked-in APKs (`maestro-app.apk`, `maestro-server.apk`) consumed by `maestro-client/`. The build's `copyMaestroAndroid` / `copyMaestroServer` finalizers update those APKs plus a `maestro-android-source.sha256` checksum.    |
| `maestro-ios-driver/`        | Host side of iOS driver wrapper (Kotlin). The actual XCTest runner lives in `maestro-ios-xctest-runner/`.                                                                                                                                                                                |
| `maestro-ios-xctest-runner/` | Swift XCTest runner that runs on the iOS device/simulator. The compiled artifacts (`maestro-driver-ios*.zip`) are checked in under `maestro-ios-driver/src/main/resources/driver-iPhoneSimulator/Debug-iphonesimulator/`.                                                                |
| `maestro-ios/`               | iOS host-side glue (small — most iOS host code lives in `maestro-client/`).                                                                                                                                                                                                              |
| `maestro-client/`            | Host-side Kotlin SDK that drives devices. Platform drivers live in `src/main/java/maestro/drivers/`: `AndroidDriver.kt`, `IOSDriver.kt`, `WebDriver.kt`, `CdpWebDriver.kt`. This is where most "auto-grant", "auto-dismiss", system-dialog handling and platform-specific quirks belong. |
| `maestro-orchestra/`         | Command execution layer. `Orchestra.kt` interprets each Maestro command, applies retries, manages the command lifecycle. Sub-packages: `error/`, `filter/`, `workspace/`, `yaml/`.                                                                                                       |
| `maestro-orchestra-models/`  | Shared command/data models (used by `maestro-orchestra/` and consumers).                                                                                                                                                                                                                 |
| `maestro-cli/`               | CLI entry point + MCP server. Mixed Kotlin (~100 files) + Swift (~56 files for iOS-related CLI bits).                                                                                                                                                                                    |
| `maestro-utils/`             | Shared utilities.                                                                                                                                                                                                                                                                        |
| `maestro-web/`               | Web (browser) driver pieces.                                                                                                                                                                                                                                                             |
| `maestro-proto/`             | Protobuf definitions shared across modules.                                                                                                                                                                                                                                              |
| `maestro-test/`              | Cross-module tests that doesn't require devices.                                                                                                                                                                                                                                         |

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

## `test-e2e.yaml` workflow contract

`.github/workflows/test-e2e.yaml` is the validation harness for both PR triggers and manual `workflow_dispatch` (e.g. validating a new Android API level or iOS version). Contract:

- **`workflow_dispatch` inputs** — `android_version` (choice enum), `app` (string, default `demo_app`), `flow` (string, optional single-flow). The `validate-inputs` job rejects `android_version <= android-29`, missing `app` workspace, or ambiguous `flow`. (See PR #3226.)
- **`pull_request` triggers** are byte-identical to the prior behaviour; manual dispatches use the new narrowing knobs.
- **`test-android` job** boots an emulator on `system-images;${android_version};google_apis;x86_64` and runs `e2e/run_tests android`.

Skills that bump platform versions (Android API levels, iOS versions) drive this workflow via `gh workflow run test-e2e.yaml --ref <branch> -f android_version=<...>`.

## Testing

Three layers — unit, integration, E2E — plus MCP-specific evals. Each layer has a different cost/coverage trade-off; default to the lowest layer that can express the test.

### Unit tests (per module, `src/test/kotlin/`)

Standard per-class tests. Stack: **JUnit 5** (`junit-jupiter-api` + `-params` + `-engine`), **Google Truth** for assertions, **MockK** for mocks. Each module's `build.gradle.kts` enables the platform via `tasks.named<Test>("test") { useJUnitPlatform() }`.

```bash
./gradlew :maestro-orchestra:test          # one module
./gradlew test                              # all modules
```

### Integration tests (`maestro-test/`)

Cross-module tests for behaviour that does **not** require a device or simulator — JS engine integration points, command orchestration end-to-end, cancellation / coroutine semantics. Notable suites:

- `IntegrationTest.kt` — full `Maestro` orchestration against an in-process `FakeDriver` (defined in `maestro-test/src/main/kotlin/maestro/test/drivers/`: `FakeDriver`, `FakeLayoutElement`, `FakeTimer`). Covers test-run cancellation (`CancellationException`, `withTimeout`, supervisor scopes) and the full command lifecycle without a real device.
- `GraalJsEngineTest.kt` / `RhinoJsEngineTest.kt` / shared `JsEngineTest.kt` — Maestro's JS extension points (`evalScript`, JS-evaluated assertions/conditions) on both supported engines. Exercises `org.graalvm.polyglot` directly.
- `FlowControllerTest.kt`, `DeepestMatchingElementTest.kt` — orchestration and view-hierarchy logic.

Stack: **JUnit 5**, **Google Truth**, **WireMock JRE8** (HTTP fakes), plus the in-house `FakeDriver` fixtures listed above. No mocks of Maestro's own classes — tests run real `Maestro` against the fakes.

```bash
./gradlew :maestro-test:test
```

### E2E tests (`e2e/`)

Smoke-test every Maestro command across Android, iOS, and Web on real fixture apps. Maestro is its own dogfood harness: the CLI executes Maestro flow YAMLs against the fixtures, asserting both the framework's commands and the platform drivers behave correctly.

Stack: **Maestro CLI itself** (dogfood) + `e2e/run_tests` shell driver + GHA workflow (`.github/workflows/test-e2e.yaml`). Fixture and suite layout is in "E2E test fixtures (`e2e/`)" above.

```bash
cd e2e && ./run_tests <android|ios|web>     # local
gh workflow run test-e2e.yaml --ref <branch> -f android_version=android-<N>   # CI
```

**Two roles for the same E2E setup.** The same suite serves both purposes — treat them identically:

1. **Regression smoke** — every PR that touches Maestro source runs the suite on the current platform versions, catching behaviour breakage on existing platforms.
2. **New-OS validation** — when launching a new Android API level or iOS version, the same flows are dispatched against the new system image to confirm Maestro still works. This is what `bump-android-version` (and the planned `bump-ios-version`) drives.

A flow breaking for either reason is a real regression — fix in `maestro-android/`, `maestro-client/`, or `e2e/demo_app/`, not in `test-e2e.yaml` (see "What NOT to do").

**Multiple apps for framework-specific coverage.** `demo_app/` (Flutter) is the default fixture and exercises every Maestro command. When a target is **framework-specific** (SwiftUI, React Native, Jetpack Compose specifics, WebView quirks, etc.), add a separate workspace under `e2e/workspaces/<app>/` with its own `.maestro/` flow YAMLs and a binary under `e2e/apps/`. Existing examples: `simple_web_view` (WebView coverage), `wikipedia` (real-world third-party app). The workflow's `app` input narrows a manual dispatch to one workspace: `... -f app=simple_web_view`.

### MCP server evals (`maestro-cli/src/test/mcp/`)

LLM-behaviour evaluations and tool-functionality tests for the MCP server inside `maestro-cli`. Stack: **`mcp-server-tester`** (npm package, run via `npx`) consuming YAML definitions (`full-evals.yaml`, `inspect-screen-evals.yaml`, `tool-tests-{with,without}-device.yaml`) plus per-platform setup scripts under `setup/`. See `maestro-cli/src/test/mcp/README.md` for the model list, scorers, and how to run.

```bash
./run_mcp_tool_tests.sh ios     # tool-functionality (fast)
./run_mcp_evals.sh ios          # LLM behaviour (slower)
```

## Conventions

- Kotlin 1.9 / JVM 17. Gradle. No DI framework — services are constructed manually.
- Protobuf for the on-device wire format (`maestro-proto/`).
- Coroutines with explicit dispatchers; `runBlocking` only at entry points.
- Exposed exceptions classify failures (retryable vs terminal) — see `maestro-orchestra/src/main/java/maestro/orchestra/error/`.

## Where Claude Code resources live

- `.claude/skills/*` — skills (workflows). Each skill's `SKILL.md` references this file for module roles.
- `.claude/agents/*.md` — subagents (e.g. `diagnose-maestro-failure.md`). Their input/output contracts are documented in each file.

## What NOT to do

- Don't fix driver-behaviour gaps by patching `.github/workflows/test-e2e.yaml` (e.g. extra `adb shell settings put …`, command-line tweaks, AVD pre-config). Workflow band-aids hide the regression from users running Maestro outside our CI. Fix `maestro-android/`, `maestro-client/`, or `e2e/demo_app/` instead so the fix ships with the driver APKs. Workflow edits are valid for shape-changes (matrix, retention, dispatch inputs) and the narrow third-party-FRE exception documented in skill files.
- Don't edit checked-in driver artifacts (`maestro-app.apk`, `maestro-server.apk`, `maestro-android-source.sha256`, `maestro-driver-ios*.zip`) by hand — they are gradle finalizer outputs.
- Don't modify existing flows in `failing/` to make them pass — that's the negative-path suite by design.
