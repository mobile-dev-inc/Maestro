# device-core Validation Harness — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor Maestro's Orchestra into a thin command router over a pluggable `ExecutionBackend`, so a real customer flow can run entirely on either the `legacy` backend (today's behavior, verbatim) or the `device-core` backend (naked, no legacy synchronization) — and measure, per step, where the two diverge.

**Architecture:** Orchestra becomes a router that owns flow control, variable interpolation, artifacts, and reporting, and dispatches each device-touching command to an `ExecutionBackend`. The backend owns selector→element resolution, synchronization/settle, tap-retry, and driver calls, plus an `open()`/`close()` lifecycle. Two implementations: `LegacyExecutionBackend` (a verbatim relocation of today's find-loop / settle / retry / provisioning) and `DeviceCoreExecutionBackend` (device-core's `connect → screen → getBy* → tap/inspect` surface with its own evidence-based synchronization). Backend selection is **per-run**. A behavior-neutral per-step trace emitter records verdict + chosen element + coordinates for every step; a differential harness diffs those traces across backends over a 59-flow corpus on a remote device pool.

**Tech Stack:** Kotlin/JVM 17, Gradle (Maestro 8.13 / device-core 9.5.1 — version gap rules out `includeBuild`), JUnit5, device-core consumed as a pinned published jar (`dev.mobile.devicecore:prototype`), remote arm-mac host pool via `run-spike-experiment` (`run_remote.sh` + `launch-avd.sh`).

## Global Constraints

- **Prime directive — do not corrupt the baseline.** The `legacy` backend is a VERBATIM relocation of today's find-loop / settle / retry AND provisioning. Relocate, do NOT rewrite, do NOT clean up while relocating. Same logic, same constants.
- **Constants that must move unchanged:** `lookupTimeoutMs = 17000L` and `optionalLookupTimeoutMs = 7000L` (currently `Orchestra.kt:136-137`, Orchestra ctor defaults — NOT in Maestro.kt); the four settle signals (`is-loading` attribute, Android `isWindowUpdating`, iOS screen-static, screenshot-diff threshold); `MaestroTimer.withTimeoutSuspend`'s hot-poll (no sleep between iterations); tap-retry `getNumberOfRetries` = 2 if `retryIfNoChange` else 1.
- **HARD GATE (blocks all device-core work):** `legacy` backend must be behaviorally identical to `stock main` across the full 59-flow corpus — ZERO divergence in verdict, chosen element, coordinates, per step. Record actual diff counts + rerun result XML in the PR. If not identical, the relocation is wrong — fix it, do not proceed.
- **Backend selection is PER-RUN.** A flow runs entirely on one backend. Per-command routing is explicitly rejected — do not reintroduce `DeviceCoreRouting` or any per-command routability predicate.
- **device-core runs NAKED.** It must never inherit legacy's find-loop or `waitForAppToSettle`.
- **Provisioning stays at run-setup altitude** (`MaestroSessionManager`, above Orchestra), parameterized by backend. Orchestra never opens/closes a device. `ExecutionBackend` carries `open()`/`close()`. Legacy `open()/close()` delegate to existing `Driver.open()/close()` byte-for-byte. device-core connects once at run start, holds the session for the whole flow, closes at run end.
- **App install ≠ driver provisioning.** Installing the corpus run's `app.apk` is a run-setup step done once before either backend opens. Backend `open()` provisions only the driver server. Pass the flow's `appId` into device-core at `open()` — NOT via process globals (`System.setProperty("devicecore.ios.bundleId", …)` is DROPPED).
- **device-core pinned.** Consume `dev.mobile.devicecore:prototype` as a version-pinned published jar (no moving `0.1.0-SNAPSHOT`), mavenLocal-first scoped to `includeGroup("dev.mobile.devicecore")`, GitHub Packages fallback. Pin a device-core build that has PR #84's self-provisioning `connect()` WITHOUT co-residence slot-lease.
- **Out of scope — stop if a task drifts here:** device-core capability build-out (separate mini-spec track); per-command routing / hybrid fallback; co-residence / slot lease / dual tenancy; multi-device / sharding / parallel; reconnect / keepalive / crash recovery; production default-on.

## Open-decision defaults taken (document in PR, do not block)

- **Interface placement:** `ExecutionBackend` + both backends live in `maestro-orchestra` (package `maestro.orchestra.backend`), NOT `maestro-client`. Reason: `maestro-orchestra-models` depends on `maestro-client` (`build.gradle.kts:32`), so client cannot reference `Command` types without a dependency cycle; orchestra already sees both `Command` models and the `Maestro`/`Driver` client. This also keeps the find-loop relocation intra-module (more plausibly verbatim).
- **Platform-first:** ANDROID (device-core has Android `inspect` Id+Text / `tap` Id-only built; iOS `tap` is a stub). The zero-divergence gate still runs all 59 flows on both platforms (it's pure Maestro); the device-core divergence run is Android-first.
- **Coordinate diff tolerance:** configurable; default ±2 px on tap center coordinates (see Phase 3 Task rationale), justified in PR. Verdict and chosen-element identity must match exactly; only coordinates carry a tolerance.
- **App-under-test identity:** harness sets `appId` at the single run boundary via `open(appId)`. Growing device-core's `TargetSelector` to name the app stays mini-spec territory. For device-core today, `open()` sets the app-binding knob once at the run boundary (not per-op) — the clean replacement for the prototype's per-op `System.setProperty`.
- **Observability (§5) seam:** capture per-step evidence from the backend `StepTrace` (already returned) plus a screenshot pulled at the **corpus-runner/host level via `adb shell screencap`**, NOT by adding a `screenshot` verb to device-core (that's capability-track double-duty). Keeps §5 out of the device-core repo.

## The shared contract — `ExecutionBackend` (every phase depends on this)

```kotlin
package maestro.orchestra.backend

import maestro.orchestra.Command
import maestro.ViewHierarchy
import maestro.DeviceInfo

/**
 * The seam. Orchestra (router) dispatches device-touching commands here.
 * ABOVE this line: flow control, variables, artifacts, reporting (stay in Orchestra).
 * BELOW this line: selector resolution, synchronization/settle, retry, driver calls.
 */
interface ExecutionBackend {
    /** Provision + connect the driver for this run. appId = the flow's app-under-test. Called once at run start. */
    fun open(appId: String?)

    /** Teardown. Called once at run end. */
    fun close()

    /**
     * Execute one already-resolved (variable-interpolated) device-touching command.
     * Returns whether the command mutated device state (preserves Orchestra.executeCommand's
     * Boolean semantics that drive timeMsOfLastInteraction) plus the per-step trace.
     */
    suspend fun execute(command: Command, context: BackendContext): CommandExecutionResult

    /** Snapshot the current view hierarchy for artifacts/reporting above the seam. */
    fun viewHierarchy(excludeKeyboardElements: Boolean = false): ViewHierarchy

    val deviceInfo: DeviceInfo
}

/** Read-only per-command inputs the backend needs from the router (timeouts, flow config). */
data class BackendContext(
    val lookupTimeoutMs: Long,          // 17000 for legacy; ignored by device-core
    val optionalLookupTimeoutMs: Long,  // 7000 for legacy; ignored by device-core
    // extended as handlers are relocated; keep additive
)

data class CommandExecutionResult(
    val mutating: Boolean,              // == today's Orchestra.executeCommand Boolean return
    val trace: StepTrace? = null,       // Phase 1 leaves null; Phase 3 populates for the differential
)

/** The unified per-step diff record. Both backends populate it; the trace emitter consumes it. */
data class StepTrace(
    val verdict: Verdict,               // PASS / FAIL / ERROR
    val chosenElement: ChosenElement?,  // null when the command resolves no element
    val declined: Boolean = false,      // device-core: command not implemented -> logged coverage gap
    val declinedReason: String? = null,
    val evidence: Map<String, String?> = emptyMap(),  // backend-specific (device-core Signal ladder, etc.)
)

enum class Verdict { PASS, FAIL, ERROR }

data class ChosenElement(
    val x: Int, val y: Int, val width: Int, val height: Int,  // bounds
    val centerX: Int, val centerY: Int,                        // the coordinate the gesture used
    val text: String?,
    val resourceId: String?,
    val index: Int?,                                           // selection index if one was used
)
```

**Router contract (Orchestra):** `executeCommand`'s `when`-branch (`Orchestra.kt:384-447`) keeps the ABOVE-seam branches (flow control, variables, AI, artifacts) in Orchestra and routes each BELOW-seam branch through `backend.execute(command, ctx)`. `Orchestra.findElement` (1422-1499) and its helpers relocate into `LegacyExecutionBackend`. `ArtifactsGenerator.captureStepHierarchy` (`ArtifactsGenerator.kt:243`) routes its `viewHierarchy()` read through `backend.viewHierarchy()` so per-step artifacts don't bypass the seam.

---

## Phase 1: Orchestra → router + `ExecutionBackend` interface

Prove the pattern on a small command set (enough to run one flow end-to-end on `legacy`), confirm the shape, then finish the whole command surface. TDD throughout; the seam tests reuse the prototype's `providerFactory` + `FakeDeviceProvider`-style rig (zero device I/O).

### Task 1.1: Define the `ExecutionBackend` interface + result types

**Files:**
- Create: `maestro-orchestra/src/main/java/maestro/orchestra/backend/ExecutionBackend.kt` (the contract block above)
- Test: `maestro-orchestra/src/test/kotlin/maestro/orchestra/backend/ExecutionBackendContractTest.kt`

**Interfaces:**
- Produces: `ExecutionBackend`, `BackendContext`, `CommandExecutionResult`, `StepTrace`, `Verdict`, `ChosenElement` (exact signatures above).

- [ ] **Step 1: Write the failing test** — a compile-level contract test that constructs a `CommandExecutionResult(mutating = true, trace = StepTrace(Verdict.PASS, ChosenElement(0,0,10,10,5,5,"OK","btn_ok",null)))` and asserts field access, and a no-op `ExecutionBackend` anonymous impl compiles and its `execute` is callable. (This pins the type surface before any behavior.)
- [ ] **Step 2: Run it, verify it fails** — `./gradlew :maestro-orchestra:test --tests '*ExecutionBackendContractTest*'` → FAIL (types undefined).
- [ ] **Step 3: Create `ExecutionBackend.kt`** with the exact contract block above.
- [ ] **Step 4: Run it, verify it passes.**
- [ ] **Step 5: Commit** — `feat(orchestra): add ExecutionBackend seam interface + result types`.

### Task 1.2: `LegacyExecutionBackend` — relocate `findElement` + tap, proven on `TapOnElementCommand`

This is the first vertical slice: move the `findElement` funnel and the `tapOnElement` handler body **verbatim** from Orchestra into `LegacyExecutionBackend`, wire Orchestra to dispatch `TapOnElementCommand` through the backend, and prove behavior is unchanged with a seam test using a fake Maestro.

**Files:**
- Create: `maestro-orchestra/src/main/java/maestro/orchestra/backend/LegacyExecutionBackend.kt`
- Modify: `maestro-orchestra/src/main/java/maestro/orchestra/Orchestra.kt` (ctor takes `ExecutionBackend`; `executeCommand` routes `TapOnElementCommand`; delete the relocated `findElement`/`tapOnElement` bodies, replace with `backend.execute`)
- Test: `maestro-orchestra/src/test/kotlin/maestro/orchestra/backend/LegacyBackendTapTest.kt`

**Interfaces:**
- Consumes: `ExecutionBackend` (1.1), `maestro.Maestro`, `Orchestra.findElement`/`tapOnElement` current bodies (`Orchestra.kt:1325-1362`, `1422-1499`).
- Produces: `LegacyExecutionBackend(maestro: Maestro) : ExecutionBackend`. `open()/close()` delegate to `maestro.driver.open()/close()` via the current path (Phase 2 formalizes provisioning; here `open()` is a no-op passthrough since MaestroSessionManager still constructs the live driver).

- [ ] **Step 1: Write the failing seam test** — construct `LegacyExecutionBackend(fakeMaestro)` where `fakeMaestro.findElementWithTimeout` returns a known `FindElementResult` with bounds (100,200,40,20); call `backend.execute(TapOnElementCommand(selector), ctx)`; assert `fakeMaestro.tap` was called with that element and `result.mutating == true`. (Mirror the prototype's `OrchestraSeamTest` style.)
- [ ] **Step 2: Run it, verify it fails** (class undefined).
- [ ] **Step 3: Relocate verbatim** — move `findElement` (+ helpers `resolveParentHierarchy`, `buildFilter`, `childOfDebugMessage`) and the `tapOnElement` body into `LegacyExecutionBackend`, keeping every `maestro.*` call, the `adjustedToLatestInteraction` timeout math, and constants identical. `execute` `when`-matches `TapOnElementCommand` → the relocated body.
- [ ] **Step 4: Wire Orchestra** — ctor gains `backend: ExecutionBackend`; `executeCommand`'s `TapOnElementCommand` branch becomes `backend.execute(command, ctx).mutating`; remove the now-relocated private methods.
- [ ] **Step 5: Run the seam test + the existing Orchestra tap tests, verify all pass.**
- [ ] **Step 6: Commit** — `refactor(orchestra): relocate findElement + tapOnElement into LegacyExecutionBackend`.

### Task 1.3: Wire backend construction through `MaestroSessionManager` + prove one flow end-to-end on legacy

**Files:**
- Modify: `maestro-cli/src/main/java/maestro/cli/session/MaestroSessionManager.kt` (construct `LegacyExecutionBackend(session.maestro)`, thread into the runners); the runners `MaestroCommandRunner.kt`, `TestSuiteInteractor.kt`, `McpViewerOrchestra.kt` (pass `backend` into `Orchestra(...)`).
- Test: an e2e-style seam test running a 3-command flow (`launchApp` → `tapOn` → `assertVisible`) through `Orchestra(fakeBackend)` proving the router dispatches BELOW-seam commands to the backend and handles ABOVE-seam commands itself.

- [ ] **Step 1:** Write the failing router test (flow of tap+assert routed to a recording fake backend; assert dispatch order + that a `defineVariables` command never hits the backend).
- [ ] **Step 2:** Run, verify fail.
- [ ] **Step 3:** Relocate `assertCommand`/`assertConditionCommand`'s `findElement` path and `launchAppCommand` into `LegacyExecutionBackend.execute`; route those branches in Orchestra.
- [ ] **Step 4:** Wire `MaestroSessionManager` + runners to construct and pass the legacy backend.
- [ ] **Step 5:** Run the router test + full `:maestro-orchestra:test` + `:maestro-cli:test`, verify green.
- [ ] **Step 6: Commit** — `refactor: route tap/assert/launch through ExecutionBackend; wire legacy backend in session setup`.

### Task 1.4: Finish the command surface

Relocate every remaining BELOW-seam / MIXED handler body into `LegacyExecutionBackend.execute` and route its branch, one command (or tight cluster) per commit, each with its existing Orchestra test kept green. ABOVE-seam handlers (flow control, variables, script/eval, AI, pure artifact sinks) stay in Orchestra.

BELOW-seam commands to relocate (from the seam map): `tapOnPoint`, `tapOnPointV2`, `backPress`, `hideKeyboard`, `scrollVertical`, `copyTextFrom`, `scrollUntilVisible`, `pasteText`, `swipe`, `assertScreenshot` (driver-screenshot half), `inputText`, `inputTextRandom`, `setPermissions`, `openLink`, `pressKey`, `eraseText`, `stopApp`, `killApp`, `clearAppState`, `clearKeychain`, `setLocation`, `setOrientation`, `waitForAnimationToEnd`, `travel`, `addMedia`, `setAirplaneMode`, `toggleAirplaneMode`, `setDarkMode`, `toggleDarkMode`, `assertDarkMode`. MIXED (`takeScreenshot`, `startRecording`/`stopRecording`): the driver call goes below, the artifact sink stays above — split at the seam.

Route `ArtifactsGenerator.captureStepHierarchy`'s `viewHierarchy()` through `backend.viewHierarchy()`.

- [ ] One TDD task per command cluster (test-first, relocate verbatim, route, keep existing tests green, commit). Expand to bite-sized steps at execution time per cluster.
- [ ] **Gate for Phase 1 completion:** full `:maestro-orchestra:test` + `:maestro-cli:test` green; the smoke set's 6 flows run end-to-end on the legacy backend locally (or emulator) with no behavior change.

---

## Phase 2: `legacy` backend relocation completeness + zero-divergence HARD GATE

Phase 1 relocates command handlers; Phase 2 completes the provisioning relocation and runs the gate. **Blocked-by:** Phase 1 complete AND Phase 3's trace emitter (needed to diff element+coords).

### Task 2.1: Provisioning lifecycle into `open()/close()` (delegate byte-for-byte)
- `LegacyExecutionBackend.open()` delegates to the existing `Driver.open()` path; `close()` to `Driver.close()` via the current `MaestroSessionManager` shutdown-hook semantics (`MaestroSessionManager.kt:126-133`). No behavior change — provisioning still happens where it happens; the backend just owns the handles. TDD with a fake driver asserting open/close delegation order.

### Task 2.2: The zero-divergence gate (execution)
- Build stock-main-with-trace-emitter (Phase 3 emitter cherry-picked onto stock main, behavior-neutral) and refactor-branch-with-legacy-backend.
- Run the **full 59** on the remote pool, both builds, same fresh `research_spike_*` AVDs, sequential per host, clean teardown.
- Diff per-step traces (verdict exact, chosen element exact, coordinates exact — the two builds share one resolution path so coordinates must be identical here, tolerance is for Phase 5 cross-backend only).
- **Deliverable:** zero divergence across all 59. Record diff counts + rerun result XML for the PR. If any divergence: the relocation broke behavior — fix and rerun. **This gate must be green before Phase 4.**

---

## Phase 3: Differential harness + corpus runner + dependency pinning (parallel with Phase 2)

### Task 3.1: Behavior-neutral per-step trace emitter
- A new `OrchestraListener` (sibling to `ArtifactsGenerator`) that, per step, writes a structured record: command index (keyed to `flow-dump.json` order), `Verdict`, `ChosenElement` (bounds + center + text + resourceId + index), backend id. Schema modeled on `triage-3-hierarchy.json`'s `bounds{x,y,width,height,centerX,centerY}`. Populates `StepTrace` from `CommandExecutionResult`. MUST NOT change find/settle/retry behavior — it only reads what the backend already resolved. Unit-tested against a fake backend; verified behavior-neutral by re-running the smoke set and confirming identical console logs with/without the emitter.

### Task 3.2: Differential diff tool + corpus runner
- A diff tool: given two trace directories (backend A, backend B) for a flow, emit per-step verdict diff, chosen-element diff, coordinate diff (configurable tolerance, default ±2px), and first-divergent-step. Coverage: every device-core `declined` step is a logged gap; aggregate a frequency distribution across the corpus.
- A corpus runner over `~/maestro-replay-harness` (59 runs): for each run, install its `app.apk` (once, run-setup), run the flow on the selected backend, pull traces + a per-step `adb shell screencap`. Sequential per host, clean teardown between (`launch-avd.sh down`).
- Remote orchestration: adapt `run_remote.sh` — supply the missing "build Maestro CLI off-box + copy in" step (the pool hosts have no gradle); enumerate hosts live from `.../macstadium/inventory/testing.yml` (`arm-m4s-239/240/241`, `arm-m2m-005/006/007`); verified-tar result pull. Detached for long pulls.

### Task 3.3: Dependency pinning (§7)
- Replace the moving `0.1.0-SNAPSHOT` with a pinned version (or SHA-stamped classifier). Keep the mavenLocal-first `includeGroup("dev.mobile.devicecore")` scoping + GitHub Packages fallback from the prototype's `settings.gradle.kts:16-33`. Resolve which device-core branch/build carries PR #84's self-provisioning `connect()` without co-residence slot-lease, publish/pin that exact build. Guard with a classpath test (like the prototype's `DeviceCoreClasspathTest`).

---

## Phase 4: `device-core` backend + transport hardening + observability

**Blocked-by:** Phase 2 gate green (Android — DONE; 38/38 GREEN).

### Task 4.0: Seam reshape — make `ExecutionBackend` backend-neutral (prereq to 4.1)

Resolves the Phase-1 carry-forward interface-shape items. Backed by two read-only audits
in the plan workspace: `seam-boundary-analysis.md` (the four non-`execute` maestro-typed
methods) and `command-vs-operation-analysis.md` (which methods are genuine device
operations vs. flow commands vs. above-seam config/reporting). Governing rules:
1. **Seam return types must be `ChosenElement`-grade** — primitives and orchestra/command
   types, never `FindElementResult`/`ViewHierarchy`/`UiElement`.
2. **A named seam method exists ONLY to move something `execute()`'s
   `(Command) → (mutating, trace, output)` signature can't carry**: a `Sink` handed IN, a
   live handle held ACROSS steps, or a value the router BRANCHES ON handed OUT. Everything
   else is either an `execute()` command, run-boundary config, or a reporting read.

Every change below is behavior-neutral for legacy (only the type/placement crossing the
seam changes, never the driver calls); verify by re-running the smoke subset through the
quad gate (still GREEN) — the full 38 is not required since no execution logic changes.

- **Drop `findElement(...): FindElementResult` from the interface (MECHANISM-LEAK).** Its
  only two router callers are the screenshot crops (`Orchestra.kt:631,970`), which consume
  ONLY `element.bounds`. Fold crop resolution BELOW the seam into `takeScreenshot` (next
  bullet): legacy resolves the crop rectangle internally exactly as today and, on invalid
  dimensions, throws a typed `InvalidCropDimensions(bounds)` the router catches and
  re-wraps into the VERBATIM command-specific `AssertionFailure` (message + `debugMessage`
  + `hierarchySnapshot()`), so the exact error text stays above the seam and no
  `FindElementResult`/`Bounds` return type crosses. No `boundsForSelector` replacement.
- **Keep `takeScreenshot` and `startScreenRecording` as named seam methods** (rule 2:
  `takeScreenshot` takes a router-owned `Sink` IN — different per caller: bundle file /
  in-memory `Buffer` for AI / temp file for pixel-diff — so it can't be an `execute()`
  command; `startScreenRecording` takes a `Sink` IN and returns a `ScreenRecording` handle
  the router holds ACROSS start→stop). Reshape `takeScreenshot` to
  `takeScreenshot(out, compressed, cropOn: ElementSelector?, optional, context)` (crop
  folded below per above). `stopRecording` needs NO seam method — the handle is
  `AutoCloseable`, the router owns it, stop = `screenRecording?.close()`
  (`Orchestra.kt:1005`, defensive close at flow end `:246`). device-core throws a typed
  `BackendUnsupportedOperation` for both → router records a coverage gap (never a crash).
- **Fold `setAndroidChromeDevToolsEnabled` into `open(...)` and DELETE it from the seam.**
  Forensics (verified): it is a per-run constant derived from `config.ext[
  "androidWebViewHierarchy"] == "devtools"`; the second init site (`Orchestra.kt:302`) is
  pure redundancy (every `executeCommands`/subflow path re-derives from the SAME root
  `config`, never a subflow config — `:227,925` pass `config`, not `subflowConfig`), so it
  cannot diverge within a run. Widen `open(appId)` → `open(appId, config: MaestroConfig?)`
  (called once at `runFlow` start, after `config` is parsed at `:217`, driver live).
  Legacy's `open` derives the flag and toggles its driver; device-core connects and
  ignores it; iOS no-ops. Delete `initAndroidChromeDevTools` and BOTH call sites
  (`:219`, `:302`); the `"devtools"` magic string moves below the seam. Not a constructor
  param — `config` isn't known at construction, and the toggle is a live-driver call.
- **Replace `viewHierarchy(): ViewHierarchy` with `hierarchySnapshot(): TreeNode?`
  (BAGGAGE → nullable reporting hook), and make `AssertionFailure.hierarchyRoot` nullable
  (`TreeNode?`).** All 9 `Orchestra.kt` callers + `ArtifactsGenerator.kt:251` take `.root`
  for an `AssertionFailure.hierarchyRoot` or a per-step artifact dump — pure reporting.
  FINDING (verified): `hierarchyRoot` has ZERO read sites repo-wide — the only
  `AssertionFailure` consumer (`TestRunner.kt:99`) reads `.debugMessage`, and
  `ArtifactsGenerator` deliberately strips the tree from artifacts
  (`ArtifactsGeneratorTest.kt:951` asserts output `doesNotContain("hierarchyRoot")`). So
  nullable-now is safe (nothing breaks); legacy passes its real tree, device-core passes
  null. device-core CAN adapt its native adblib window tree into a `TreeNode` later (plain
  data class of primitives) if/when artifacts matter — additive, zero current risk.
- **Drop `deviceInfo` from the interface (right-size).** Sole use `Orchestra.kt:170` reads
  only `.platform` to construct the GraalJS engine. Platform is a provisioning-time fact —
  inject `platform: Platform` into Orchestra / the `jsEngineFactory` at construction
  instead of querying the backend.
- **Keep `evaluateCondition(...): Boolean` unchanged (FUNDAMENTAL — the template).**
  Scripts are evaluated above the seam; the backend answers a bare Boolean, no maestro
  type crosses.
- **Resulting seam:** `open(appId, config)` / `close()` / `execute(command, context)` /
  `evaluateCondition(condition, …): Boolean` / `hierarchySnapshot(): TreeNode?` /
  `takeScreenshot(out, compressed, cropOn, optional, context)` /
  `startScreenRecording(out): ScreenRecording`. Every return type is backend-neutral;
  device-core serves `execute`/`evaluateCondition`/`open`/`close`, returns null from
  `hierarchySnapshot`, and throws typed-unsupported from the two capture methods.

### Task 4.0b: Complete the seam — route `ArtifactsGenerator` device access through the backend

Finishes what Task 1.9 deferred (see its `ArtifactsGenerator.kt:50` comment): the seam
must be the SOLE device path, or a device-core run silently hits legacy `maestro`.

- **Route step-screenshot capture through the backend.** `ArtifactsGenerator.kt:294`
  calls `ScreenshotUtils.takeDebugScreenshot(maestro = maestro)` directly (M1 bypass).
  Route it through `backend.takeScreenshot`, PRESERVING its best-effort semantics:
  `takeDebugScreenshot` returns null on failure and the step continues, whereas
  `backend.takeScreenshot` throws — so catch at the artifact site and treat a throw as a
  skipped artifact (behavior-identical for legacy). device-core throws unsupported → the
  per-step artifact is simply absent; §5 host-level `adb screencap` (Task 4.3) fills it.
- **Route full-run recording through the backend.** `ArtifactsGenerator.kt:313`
  (`maestro.startScreenRecording`, the Important#2 bypass) → `backend.startScreenRecording`.
- **Assess device-artifact capture** (`CapturedDeviceArtifact` / `DeviceArtifactCapturer`,
  logs etc.): route through the backend if it fits the seam, else document it as an
  explicitly-legacy/host concern. Goal: drop the `maestro` param from `ArtifactsGenerator`
  device access entirely (or narrow it to a documented, non-device use).
- Gate stays GREEN (artifacts are ABOVE the behavioral gate); verify legacy artifact
  output is byte-unchanged on the smoke subset.

### Task 4.1: `DeviceCoreExecutionBackend` (naked)

Implements the Task-4.0 `ExecutionBackend` seam against maestro-device-core's
`connect → screen → getBy* → tap/inspect` API. Android-first. Reuse the prototype's
device-core code (READ-ONLY source, adapt into the main tree — never modify the
worktree): `.claude/worktrees/milestone4-assertvisible-devicecore/maestro-orchestra/src/main/java/maestro/orchestra/devicecore/`
(`AssertVisibleVerdict.kt`, `DeviceCoreAssertRouter.kt`, `DeviceCoreRouting.kt`) and its
test rig `.../src/test/kotlin/maestro/orchestra/devicecore/` (`FakeDeviceProvider.kt`,
`DeviceCoreClasspathTest.kt`, and the `*Test` files).

**Build wiring (this task, so it compiles):** add to the main tree, mirroring the prototype:
`maestro-orchestra/build.gradle.kts` → `implementation("dev.mobile.devicecore:prototype:0.1.0-SNAPSHOT")`;
`settings.gradle.kts` → the mavenLocal-first block scoped `includeGroup("dev.mobile.devicecore")`
plus the GitHub-Packages fallback. (Version pinning is Task 4.3; use the `~/.m2` SNAPSHOT now.)

**Backend:** `DeviceCoreExecutionBackend(appId?, providerFactory = { AndroidDeviceProvider() })`
in `maestro-orchestra/.../backend/`. Reuse-classes (`AssertVisibleVerdict`, the selector→
text-query routability from `DeviceCoreRouting`, the `ElementEvidence`/`Resolution` mapping) go in
`maestro-orchestra/.../devicecore/` (adapted from the prototype, Android target). DROP the prototype's
co-resident `DeviceCoreRouting`/`DeviceCoreAssertRouter` *wiring* (the `fromEnvOrNull`/`System.setProperty`
bolt-on) — keep only the pure logic.

- `open(appId, config)`: `connect(TargetSelector(TargetId.ANDROID_EMU))` ONCE, hold the `Device` for the
  flow, set the app-binding at the run boundary (clean replacement for the prototype's process-global
  `System.setProperty("devicecore.ios.bundleId", …)`), IGNORE `config.ext["androidWebViewHierarchy"]`
  (legacy Android-webview concern). NO find-loop, NO `waitForAppToSettle`.
- `close()`: close the `Device` (stops the server).
- `execute(command, context)` maps: `AssertVisibleCommand`/`AssertNotVisibleCommand` with a routable
  literal-text selector → `screen.getByText(text, match)[.nth(i)].inspect()` → `AssertVisibleVerdict.pass`
  → `StepTrace(verdict)`; `TapOnElementCommand` with a literal id selector → `screen.getById(id).tap()`;
  **everything else → `StepTrace(declined = true, declinedReason = …)`** (a non-routable selector — regex,
  relative, traits — also declines). Never a crash.
- `evaluateCondition(condition, …): Boolean` for bare `visible`/`notVisible` literal-text conditions →
  same `getByText` + `inspect` + verdict path (reuse the routability check; a non-routable/`platform`/
  script condition is out of device-core's scope — decline semantics per the router's guard).
- `hierarchySnapshot()` → `null`. `takeScreenshot` / `startScreenRecording` → throw a typed
  `BackendUnsupportedOperation` (the Task-4.0 router path records it as a coverage gap, never a crash).
- `Resolution.Unavailable` (socket refused / driver down) → `AssertVisibleVerdict` throws
  `DeviceCoreUnavailable` — an infra failure, distinct from a fail verdict; surface it as an ERROR trace,
  not a FAIL.
- **Tests (reuse `FakeDeviceProvider`):** assertVisible/notVisible → verdict; tapOn(id) → tap invoked;
  unsupported command → declined; non-routable selector → declined; `open`/`close` lifecycle; a
  classpath test proving the device-core dep resolves. No real device required.

### Task 4.1b: Wire device-core into the CLI (selection + real dims + capture decline)

Makes `DeviceCoreExecutionBackend` runnable end-to-end for the Phase-5 differential. Backed by the
4.1b investigation (units/selection/router surface, in the ledger). Three parts:

- **Backend selection (maestro-cli).** At the two prod `test`-flow construction sites —
  `MaestroCommandRunner.kt:103` and `TestSuiteInteractor.kt:190` — choose the backend via a small
  shared factory: if `System.getenv("MAESTRO_DEVICECORE_ASSERT") == "1"` AND
  `maestro.cachedDeviceInfo.platform == Platform.ANDROID`, construct
  `DeviceCoreExecutionBackend(appId = <flow MaestroConfig.appId>, providerFactory = { AndroidDeviceProvider() },
  screenSize = maestro.cachedDeviceInfo.widthPixels to maestro.cachedDeviceInfo.heightPixels)` and pass it
  as `backend =`; else keep today's `LegacyExecutionBackend(maestro)`. Pass `platform = Platform.ANDROID`
  for the device-core path (else `maestro.cachedDeviceInfo.platform` as today). `McpViewerOrchestra` is
  not a real run — leave it. Env var (not a CLI flag) — no Picocli plumbing.
- **Real screen dims (units RESOLVED).** device-core Android `ElementEvidence.bounds` are RAW DEVICE
  PIXELS (uiautomator `boundsInScreen`, no density scaling — verified in the device-core driver).
  `maestro.cachedDeviceInfo.widthPixels/heightPixels` is the SAME pixel space (on Android
  `widthPixels == widthGrid`), so passing it as `screenSize` makes `AssertVisibleVerdict`'s far-edge
  check correct with NO density factor. Use `widthPixels/heightPixels` for intent-clarity.
- **Router capture-decline (Orchestra).** The 6 ABOVE-seam handlers that call `backend.takeScreenshot`/
  `backend.startScreenRecording` directly — `Orchestra.kt:503,541,576` (the 3 AI handlers, skip BEFORE
  calling the AI engine since the screenshot is their only input), `:635` (assertScreenshot), `:975`
  (takeScreenshot), `:999` (startRecording) — must catch `BackendUnsupportedOperation` and SKIP the
  command as a coverage gap: return `false` (non-mutating), log. **Do NOT emit a `StepTrace`** for these:
  legacy emits none for above-seam commands, so a device-core trace here would misalign the per-step
  streams and manufacture a false divergence. Behavior-neutral for legacy (never thrown).
  `ArtifactsGenerator`'s screenshot/recording calls are ALREADY guarded by its own try/catch
  (`captureScreenshot :301-307`, `startFullRunRecording :314-319`; `BackendUnsupportedOperation` is a
  `RuntimeException`) — no change there.
- **Tests:** the selection factory picks device-core iff env+Android, legacy otherwise; each of the 6
  handlers skips (not crashes) when the backend throws `BackendUnsupportedOperation` (stub backend),
  returning non-mutating with no trace emitted.
- **Phase-5 runtime NOTE (not this task):** a device-core run still connects `maestro` (for
  session + `cachedDeviceInfo`) AND device-core's adblib driver to the same emulator — watch for
  dual-instrumentation contention when the corpus runs; handle empirically in Phase 5.

### Task 4.1c: device-core execution semantics + trace observability (from the end-to-end smoke)

The end-to-end smoke (device-core selected via `MAESTRO_DEVICECORE_ASSERT=1` on a real emulator) proved
device-core RUNS (connects, dual maestro+device-core connection works, pixel dims match) but exposed
two integration bugs unit tests couldn't (the `FakeDeviceProvider` returns canned Resolved evidence):

**Bug 1 — device-core outcomes must THROW, not return a verdict.** Orchestra derives the trace verdict
from the lifecycle (`Orchestra.kt:854-856`: returns-normally → `Completed`/PASS; throws `MaestroException`
→ FAIL; throws other → ERROR) — the emitter reads only `trace.chosenElement`, never `trace.verdict`
(`StepTraceEmitter.kt:96`). Legacy signals a failed assert by THROWING `MaestroException.AssertionFailure`
(`LegacyExecutionBackend.kt:700`) and a not-found tap by `MaestroException.ElementNotFound` (`:678`);
`currentStepTrace` is only set when `execute()` returns (`Orchestra.kt:457`), so a thrown command
correctly yields FAIL with no `chosenElement`. device-core's `executeAssert`/`executeTap` instead RETURN
`StepTrace(verdict=FAIL/ERROR)` without throwing → Orchestra reads PASS → **every failed device-core
assert/tap silently passes** (proven: a required assert on a nonexistent element returned exit 0). Fix:
- `executeAssert`: visible → return `CommandExecutionResult(mutating=false, trace=StepTrace(chosenElement=…))`;
  not-visible → `throw MaestroException.AssertionFailure(...)` (→ FAIL); `DeviceCoreUnavailable` → RETHROW
  (don't catch into an error trace) so it surfaces as ERROR (non-`MaestroException`).
- `executeTap`: success → return (mutating, chosenElement); element-not-found / tap failure → throw
  `MaestroException.ElementNotFound` (→ FAIL, matches legacy); `DeviceCoreUnavailable` → rethrow (ERROR).
- Declined commands (unsupported verb, non-routable selector, gesture-modifier tap) KEEP returning
  `CommandExecutionResult(trace=StepTrace(declined=true, …))` — a declined step is a coverage gap and
  must NOT fail the flow.
- Update the unit tests: assert the THROW (absent element → `AssertionFailure`; `Unavailable` → propagates;
  tap-not-found → throw) rather than a returned FAIL verdict.

**Bug 2 — trace can't distinguish backends or coverage gaps.** `StepTraceEmitter.backendId` is hardcoded
`"legacy"` (`:52` default; `Orchestra.defaultStepTraceEmitter :1108` never passes it), and the emitted
record omits `declined`. For the Phase-5 differential/coverage report:
- Add `val backendId: String` to `ExecutionBackend` (`"legacy"` / `"devicecore"`); thread it into the
  `StepTraceEmitter` construction (via `defaultStepTraceEmitter`, from the run's backend). Legacy stays
  `"legacy"` — schema/label unchanged.
- Record `declined` (and `declinedReason`) in the emitter's output record — **only when true / non-null**
  (`JsonInclude.NON_NULL`) so LEGACY traces stay byte-identical (legacy never declines) and the Phase-2
  gate contract is unperturbed. This lets Phase 5 rank declined-command frequency.
- (Phase-5 classifier `classify.py`/`diff_traces.py` will treat a `declined` step as a coverage gap, not a
  divergence — that change belongs to Phase 5, not this task.)

**OPS for Phase 5 (not code):** device-core provisioning shells `adb`, so a device-core run needs `adb`
on `PATH` (`export PATH=$HOME/android-sdk/platform-tools:$PATH`); the differential run env must set it.

### Task 4.2: Transport hardening (in device-core repo, republished jar)
- Op-level timeout on `rpc()` (`LineRpc.kt:10-14`): apply `SocketPrecondition`'s bounded pattern (`connect(addr, timeout)` + `soTimeout`) to the op path.
- Typed death on the tap path (`Resolver.kt:159-164`): `resolveLive` catches socket failure → a typed `Resolution.Unavailable`-equivalent the backend classifies, symmetric with `inspect`. (No reconnect/recovery — out of scope.)
- Republish + re-pin (Task 3.3).

### Task 4.3: Observability (§5)
- Per-step evidence already rides `StepTrace.evidence` (device-core `Signal` ladder + resolution). Add the host-level `adb shell screencap` per step in the corpus runner (Task 3.2) — no device-core `screenshot` verb. A declined/failed device-core step is triageable from evidence + screenshot.

---

## Phase 5: device-core corpus run + divergence/coverage report + PR

**Blocked-by:** Phase 4, Phase 3.

- Run the full 59 on the device-core backend (Android-first) on the remote pool; diff against the legacy traces from Phase 2/3 with the ±2px coordinate tolerance.
- Produce the divergence + coverage report: element-selection divergence frequency/severity, declined-command frequency ranking (what to build next), first-divergent-step per flow, settle-fidelity observations.
- Assemble the self-contained PR: architecture + seam design, the Phase 2 gate evidence (actual diff counts + rerun XML), the final divergence + coverage report, and every open-decision default taken (this section).

---

## Phase 1 review carry-forward (resolve before Task 4.1; note in PR)

The Phase 1 whole-branch review passed (0 Critical; prime directive held — Orchestra device-free, 34 commands routed, single clock, no divergent duplicates). Two Important items are Phase-4 interface-shape concerns, NOT Phase-1 defects, and do NOT threaten the zero-divergence gate:

- **Interface accretion (load-bearing for Phase 4).** `ExecutionBackend` gained per-command methods (`takeScreenshot`, `startScreenRecording`, `setAndroidChromeDevToolsEnabled`, and `findElement` returning legacy `FindElementResult`) that Orchestra's above-seam handlers (AssertScreenshot, TakeScreenshot, AI, crop) call DIRECTLY — not via `execute()`'s decline path. A `DeviceCoreExecutionBackend` structurally cannot serve or "decline" these (device-core has no screenshot verb — host-level `adb screencap` per §5 — and uses `getBy*`/`inspect`, not legacy `FindElementResult`). **Before Task 4.1, decide:** make these `execute()`-routed declinable commands, or keep them above-seam served by a host-level capturer, and shrink the interface accordingly.
- **`ArtifactsGenerator.kt:313` `fullRunRecording = maestro.startScreenRecording(...)` bypasses the seam.** Behaviorally identical to stock main (still `maestro.*`) so the gate is unaffected; but on a device-core run it silently runs on legacy `maestro`. Route it, or decide "recording always uses host-level capture," before Phase 4/5.

Parked minors (resolve during the Phase-4 interface reshape): `BackendContext.lookupTimeoutMs`/`optionalLookupTimeoutMs` are dead for legacy (legacy reads ctor fields — two sources of truth, no divergence risk today); `BackendContext.timeMsOfLastInteraction` defaults to `System.currentTimeMillis()` (a context built without it gets a full — not truncated — timeout; consider a required param). Documented decision: `MaestroSessionManager`/runner backend-**selection** wiring was deferred (ctor defaults to `LegacyExecutionBackend`); per-run selection is Phase-4 plumbing.

## Self-Review

- **Spec coverage:** §1 router → Phase 1; §2 legacy + gate → Phase 2 (+ emitter dep from Phase 3); §3 device-core backend → Phase 4.1; §4 transport hardening → Phase 4.2; §5 observability → Phase 4.3; §6 differential harness + corpus sizing → Phase 3.2 + Phase 5; §7 versioning → Phase 3.3. Device connection/provisioning/lifecycle → Global Constraints + Phase 2.1 + Phase 4.1. Baseline trap → Global Constraints + Phase 2.2. Open questions → Open-decision defaults.
- **Sequencing correction vs spec:** the spec lists §3 harness as "parallel with §2." Recon showed the per-step trace instrument does not exist yet, and the gate diffs verdict+element+coordinates — so the trace emitter (Phase 3.1) is a hard prerequisite for the Phase 2 gate execution. Captured as a blocked-by. Not a spec contradiction — the emitter is still built in parallel; only the gate *run* waits on it.
- **Placeholder scan:** Phases 2–5 are intentionally task-level, to be expanded to bite-sized steps at execution time when the realized interface + gate outcomes exist; Phase 1 is bite-sized. This is honest sequencing (later steps depend on earlier realized types), not deferred specification of current work.

---

## PHASE 4/5 REFRAME (2026-08-10) — supersedes Task 4.2 + the original Phase 5

Driven by the end-to-end smoke (Android `UiAutomation` is exclusive) + human decisions + device-core `ROADMAP.md`.

**Decisions (authoritative):**
1. **Mutually-exclusive drivers.** maestro's driver and device-core both need the singleton Android `UiAutomation` → they cannot co-reside. Need a `DriverKind {MAESTRO, DEVICECORE}` chosen ONCE **before any provisioning**; only one driver is ever installed. CLI applies it in `MaestroSessionManager`; the decision type + session abstraction live in `maestro-orchestra` so the (out-of-repo) worker reaches the same switch. NO co-resident drivers, ever.
2. **Visibility is device-core-native.** `inspect(): ElementEvidence` carries `actionability: Actionability` with `visible: Signal` (from `isVisibleToUser`) — `maestro-device-core/.../api/Api.kt:201,308`. Rewrite device-core `assertVisible`/`notVisible` off `resolution == Resolved && actionability.visible.value` (+ `Resolution.NotFound`→not-visible, `Unavailable`→infra ERROR). **DELETE `screenSize`, the box-within-screen proxy, and every `deviceInfo`/screen-dims dependency.** Platform on the device-core path = `Platform.ANDROID` constant (that's the selection predicate).
3. **NO stopgaps.** A capability device-core lacks → the flow **aborts and logs** (a coverage gap) — never a Maestro-side adb call, never a silent skip. Parity: anything the production path needs (e.g. log capture), device-core owns via its own adblib, or it's an owed gap. There is no adapter layer.
4. **`ROADMAP.md` IS the coverage ranking** (device-core repo; per-verb, per-platform, sequenced by customer-suite %, from the `legacy-command-coverage` spike). device-core today ≈ P0 ~3% (`connect/screen/getById/getByText/selector/nth/inspect/tap`(android)/`launchApp`-quick); everything else (`waitFor/waitUntilGone/clearState/setPermission/scroll(To)/inputText/swipe/back` + observability `deviceInfo/hierarchy/screenshot/deviceLog`) is **owed**. So the harness's job is NOT coverage discovery (done) — it is **fidelity**: prove device-core's implemented verbs produce the same verdict + element choice as the legacy oracle on real screens (per-step, on reachable steps; a solo device-core run aborts early on almost every flow, by design).

**Task list (supersedes 4.2 + original Phase 5):**
- **Task 4.D1 — Verdict rewrite** (`maestro-orchestra`): device-core `assertVisible`/`notVisible` off `inspect().actionability.visible` + `resolution`; delete `screenSize` (ctor param + box proxy `AssertVisibleVerdict`). If `inspect()` doesn't fully populate `actionability` on the locate path, that's a device-core-repo gap — expose it there. Update tests (fake evidence carries actionability). Self-contained; do first.
- **Task 4.D2 — Driver-selection seam**: `DriverKind` chosen once pre-provision; `MaestroSessionManager` provisions EITHER maestro's driver OR device-core's `Device` only (no `Maestro.android(openDriver=true)` on the device-core path); no maestro object on that path; `platform=ANDROID`; abstraction in `maestro-orchestra` (worker-reachable); selection predicate = the current `MAESTRO_DEVICECORE_ASSERT=1` + Android, moved off `maestro.cachedDeviceInfo`. Unimplemented verbs abort+log. Design pass first (session/`Maestro`-coupling), then SDD.
- **Task 4.D3 — transport hardening** (was 4.2): only if the SOLO device-core run proves it needs `LineRpc` op-timeout / `Resolver` typed death. Re-evaluate after 4.D2 runs.
- **Phase 5 (reframed) — Fidelity differential**: run device-core SOLO on the corpus; per reachable step (`assertVisible`/`tapOn`-id) compare verdict + element vs the legacy trace; abort+log at the first owed verb; report per-flow reached-depth + verdict/element fidelity. Coverage ranking = cite `ROADMAP.md`, don't re-derive.

**OPS:** device-core provisioning shells `adb` → `adb` on `PATH` for any device-core run.
