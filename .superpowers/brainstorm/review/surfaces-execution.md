# Architecture Review — L1 (Surfaces) & L3 (Execution Engine)

> Scope: the SURFACES layer (YAML / MCP / SDK / CLI / Studio as swappable adapters over a
> Core API) and the host-side EXECUTION ENGINE (Command IR → selector resolution →
> event-driven sync → typed retry/timeout → trace emission), as proposed in
> `worked-example-core-contract.md` + `research/`.
>
> Verdict up front: **the L1 adapter framing is sound and ~70% already latent in the code,
> but it is undercut by an "import whatever is importable" coupling that the proposal does
> not name. The L3 typed-IR/error/trace pieces are correct and mostly cheap. The single
> load-bearing claim that does NOT survive contact with the codebase + PR history is
> "host-side event-driven synchronization driven by device-pushed idle/changed signals."
> Maestro already tried the device's native idle signal on BOTH platforms and ripped it out
> because it is unreliable, and neither transport can push today.**

---

## PART 1 — Maestro Today (with PR rationale)

### 1.1 The execution engine is `Orchestra` + `Maestro` + `Driver`, and it is callback-shaped, not result-shaped

`maestro-orchestra/.../Orchestra.kt` (1676 lines) is the engine. It is **not** a function
`run(Command) -> CommandResult`. It is a flow runner that walks `List<MaestroCommand>` and
drives a set of **caller-supplied callbacks**:

```
onFlowStart, onCommandStart, onCommandComplete,
onCommandFailed: (Int, MaestroCommand, Throwable) -> ErrorResolution,   // default: { _,_,e -> throw e }
onCommandWarned, onCommandSkipped, onCommandReset, onCommandMetadataUpdate, onCommandGeneratedOutput
```

The control flow uses **exceptions as the primary signal** (`Orchestra.kt:271-302`):
`executeCommand` throws `MaestroException` on failure; optional commands are re-thrown as
`CommandWarned`; skipped subflows throw a private `CommandSkipped` object; the generic
`catch (e: Throwable)` calls `onCommandFailed(...)` whose return (`FAIL`/`CONTINUE`) decides
whether to stop. This is the proposal's "Dimension 3" pain in situ: **the engine's public
contract is a thrown exception plus a callback, not a value.**

The `Maestro` client (`maestro-client/.../Maestro.kt`, 720 lines) is the cross-platform action
layer over a single `Driver` (`Driver.kt`, 113 lines, a pull-only interface:
`tap`, `inputText`, `viewHierarchy`/`contentDescriptor` (full tree), `waitForAppToSettle`,
`waitUntilScreenIsStatic`, `takeScreenshot`, `capabilities()`).

### 1.2 Selector resolution = poll a full hierarchy until match or timeout

`findElement` (Orchestra) → `maestro.findElementWithTimeout` (`Maestro.kt:432-451`):

```kotlin
val found = MaestroTimer.withTimeoutSuspend(timeoutMs) {
    hierarchy = initialHierarchy ?: ViewHierarchy.from(driver, false)   // FULL dump
    filter(hierarchy.aggregate()).firstOrNull()                          // host-side filter over flattened tree
}
```

`buildFilter` (Orchestra.kt:1378-1525) is where Maestro's rich selector language lives
(textRegex, idRegex, size, below/above/leftOf/rightOf, containsChild, containsDescendants,
traits, enabled/selected/checked/focused, css, index, `deepestMatchingElement`,
`clickableFirst`). **All matching, scoring, `index`/nth, and relative geometry is host-side
over `aggregate()` (the whole flattened tree)** — which is exactly the model the proposal
keeps ("host owns all matching") but over a *full* tree, not a *targeted* observe.

### 1.3 Synchronization = poll-and-diff "settle", and it differs sharply iOS vs Android

`waitForAppToSettle` is the heart of sync and is **platform-divergent**:

- **Shared fallback** (`ScreenshotUtils.waitForAppToSettle`, `ScreenshotUtils.kt:38-74`):
  capture **full hierarchy every tick**, compare by structural equality, also honor an
  `is-loading` root attribute; loop up to ~10×200ms (or until `timeoutMs`). This is the
  "pay the 8s iOS snapshot N times to *infer* idleness" loop the research flags.
- **iOS** (`IOSDriver.kt:486-493`): tries `waitUntilScreenIsStatic(3000)` FIRST — a
  **screenshot pixel-diff** (`isScreenStatic()`), not a hierarchy dump — and if the screen
  is static, returns `null` (skip the hierarchy entirely). Only if not static does it fall
  back to the shared hierarchy poll. So iOS already uses a *cheaper* idle detector.
- **Android** (`AndroidDriver.kt:738-768`): when `appId` is known, polls the on-device
  gRPC RPC **`isWindowUpdating`** in a loop (a device-side "am I busy" bit), then hierarchy-
  diffs; otherwise the shared hierarchy poll.

Tap retry is itself sync-driven (`Maestro.kt:297-401`): after each `driver.tap`, call
`waitForAppToSettle`; if the hierarchy (or, in the screenshot path, the screenshot) changed,
the tap "took" and we proceed — otherwise retry. `getNumberOfRetries` (`Maestro.kt:275-277`)
returns **2 if `retryIfNoChange` else 1.** This is a *did-the-UI-react* retry, NOT an infra
retry, and it is keyed on the same poll-and-diff substrate.

### 1.4 The error taxonomy is flat, value-less, and split across two channels

`maestro-client/.../Errors.kt`: `sealed class MaestroException` with ~17 leaf types
(`ElementNotFound`/`AssertionFailure`, `AppCrash`, `DriverTimeout`, `UnableToLaunchApp`,
`UnableToSetPermissions`, …). It has **no `category`, no `retryable`, no stable machine
`code`, no near-miss `candidates`.** `AssertionFailure` carries `hierarchyRoot` +
`debugMessage` (good for a human, but the consumer must *re-walk the tree* to find
near-misses — precisely what the proposal's `candidates[]` hands over directly).

Worse, **infra failures live OUTSIDE this hierarchy**: `DeviceUnreachableException`
(`DeviceUnreachableException.kt`) is a bare `RuntimeException`, NOT a `MaestroException`. Its
own docstring says "this is an infrastructure failure, not a test failure… no flow-level
retry" — i.e. the `category`/`retryable` distinction *exists in prose* but is encoded as a
*Java type you must `catch` separately*. And in `MaestroCommandRunner.onCommandFailed`
(`MaestroCommandRunner.kt:144`): `if (e !is MaestroException) throw e`. So the most-retryable
class of failure (transport death) propagates as an **uncaught throw past the result
callback** — the exact "values-vs-thrown" defect the comparison doc calls A's decisive win.

### 1.5 The four consumers and the "import whatever is importable" coupling

There is no Core facade; each consumer **reconstructs the engine wiring by hand** and reaches
directly into orchestra/client internals. Confirmed consumers in this OSS tree:

| Consumer | Entry | What it reaches into |
|---|---|---|
| **CLI test runner** | `MaestroCommandRunner.runCommands` (`maestro-cli/.../runner/MaestroCommandRunner.kt`) | Constructs `Orchestra` with 9 callbacks; maintains its own `IdentityHashMap<MaestroCommand, CommandStatus>` + `CommandMetadata`; reaches into `FlowDebugOutput`, `CommandDebugMetadata`, `CompositeCommand.subCommands()`; decides retry policy in `onCommandFailed` (`if e !is MaestroException throw e`); takes its own debug screenshots. |
| **CLI suite runner** | `TestSuiteInteractor` (`.../runner/TestSuiteInteractor.kt:187`) | A *second, independent* copy of the same `Orchestra(...)` callback wiring + `IdentityHashMap` + `debugOutput` plumbing. Duplicated, not shared. |
| **MCP** | `RunTool` + `McpViewerOrchestra` (`maestro-cli/.../mcp/...`) | `McpViewerOrchestra.create(maestro)` builds *yet another* `Orchestra` with its own callbacks, its own process-scoped `AtomicInteger` command ids, its own `IdentityHashMap`, its own PENDING-sweep + `throw error` in `onCommandFailed`. `RunTool.runInline` writes inline YAML to a **temp file** and re-parses it (`YamlCommandReader.readCommands`) — YAML-as-API round-trip. `inspect_screen` calls `maestro.viewHierarchy()` (full dump) and reformats host-side via a bespoke `ViewHierarchyFormatters.extractCompactJsonOutput`. Errors are flattened to `e.message` strings in JSON (`catch (e: Exception)`), discarding all type/category. |
| **Studio** (historical) | `maestro-studio/server/.../DeviceService.kt` (deleted in **#3299**, commit `f0da81b7`) | Built its *own* `Orchestra(maestro, onCommandFailed = { …FAIL })`, parsed YAML via `MaestroFlowParser.parseCommand`, executed, and **serialized the `MaestroCommand` list back to JSON** over an HTTP/SSE endpoint. Re-implemented device-screen streaming as an infinite SSE loop calling `getDeviceScreen` (full hierarchy + screenshot) and swallowing all exceptions. |

**The coupling is structural, not incidental.** Every consumer:
1. depends on `maestro-orchestra` + `maestro-client` directly (no narrow API package);
2. re-derives "the trace" by hand from callbacks into a private `IdentityHashMap`/`FlowDebugOutput`;
3. **owns its own error-resolution and retry policy** inside `onCommandFailed`, so retry semantics are *not* a property of the engine — they are re-decided per surface;
4. consumes the full `MaestroCommand` model and `CompositeCommand.subCommands()` tree, so the IR is *de facto* public API already (validating the proposal's "the typed model exists, just surface it"), but with no `schemaVersion` and no adapter boundary.

The 4th "consumer" in the prompt — the **cloud worker** — is not in this OSS repo. `CloudInteractor`
just uploads flows to the proprietary mobile.dev cloud; the cloud worker presumably embeds the
same `maestro-orchestra` engine out-of-tree, which only sharpens the argument: a private fork of
the engine wiring is the most expensive form of "import whatever is importable."

### 1.6 PR archaeology on load-bearing execution choices

| Choice | PR / commit | Why it was added | Still valid? |
|---|---|---|---|
| `adjustedToLatestInteraction(timeout)` (`Orchestra.kt:1572`) + retries 2→1 + `waitUntilVisible` default false | **#388** "Tweaks: speeding up Maestro flows" (`d71b1934`, Nov 2022), follow-up **#404** (`4a9f5a55`) | Explicit: *"Starting timeout countdown from the latest interaction/mutation … instead of current time. This makes consequent Optional steps and Assertions much faster as they can just assume that hierarchy did not change and are going to fail fast."* A perf hack that **bakes a heuristic into the timeout**: time already elapsed since the last mutating action is *subtracted* from the lookup budget, on the assumption that a non-mutated screen won't suddenly produce the element. | Load-bearing and **subtle**. It couples timeout semantics to the poll-and-diff settle model. In an event-driven world ("wait until X appears, fed by change pushes") this discount is meaningless/harmful — it would shorten waits based on host wall-clock, not on whether the device actually went idle. Any rearchitecture MUST consciously re-derive or drop it; silently porting it will reintroduce flake. |
| iOS settle = screenshot-diff first, skip hierarchy if static | **#734** "Improve tapOn command execution times on iOS" (`c59cfbb5`, Feb 2023) + **#787** "Remove hierarchy request for waitForAppToSettle (iOS)" (Feb 2023) | Perf: full XCUITest snapshots are ~8s on dense/RN trees; screenshot pixel-diff is far cheaper, and when static there's no need for a fresh hierarchy. | Valid and is *already* a partial form of the proposal's "cheaper idle detection." It is also evidence the team treats sync as a perf-critical, platform-specific concern — not a clean cross-platform primitive. |
| Android `isWindowUpdating` idle RPC, capped at ~1s | **#953** "[MOB 1985] check window updates while waitForAppToSettle" (`6e74df09`) | Animations cause taps to resolve to stale coordinates ("tap succeeds but nothing happens"). The device-side window-updating bit gives the screen time to settle. **Capped at 1s because "there could be timers, videos, and fillers running on the screen which might give the impression that the screen is not settled but it is."** | Valid — and it is the closest existing thing to "device tells host it's busy/idle." But the 1s cap is an explicit admission that the device's own idle signal **over-reports busy** (false-never-idle) on real screens. |
| **Disable XCTest native quiescence entirely** | **#2332** "disable the quiescence checks for XCUIApplicationProcess completely" (`443fdfc4`) | THE smoking gun. Apple's `waitForQuiescence` hung **60+ seconds** on certain screens: log shows `Wait for app to idle … App animations complete notification not received, will attempt to continue` at `t=61.40s` then again `t=122s`. Maestro swizzles `waitForQuiescenceIncludingAnimationsIdle{,:isPreEvent}` to OFF and rolls its own poll-and-diff. | Valid, and **directly fatal to the proposal's event-driven thesis** (see Part 4). The platform's native idle/changed signal is *unreliable in exactly the way that matters* (perpetual animations, spinners, background work → never-idle). Maestro already paid for this lesson. |
| Studio depends on CLI/orchestra internals | Historical; **#3299** (`f0da81b7`, May 2026) **deleted the bundled Studio module** in favor of a separate desktop app | The bundled web Studio (`maestro-studio:server`) `implementation(project(":maestro-orchestra"))` and built its own `Orchestra`. #3299 dropped the module from the CLI artifact. | The *specific* "Studio bundled inside CLI" coupling is now gone, but the **pattern** persists in MCP and the desktop app (which presumably still consumes the same engine). The untracked `maestro-studio/` in the working tree is stale build output, not live code. |

---

## PART 2 — How Other Frameworks Separate Public Surface from Execution Engine

### Appium — protocol IS the seam (closest analog, and the cautionary tale)
- **Surface/engine split:** the W3C WebDriver JSON protocol is the public contract; per-platform **drivers** (`appium-xcuitest-driver`, `appium-uiautomator2-driver`) are swappable engines behind it. Clients in any language are pure adapters over the wire protocol. This is *exactly* the L1-adapters-over-a-typed-core shape the proposal wants, proven across a decade and many languages.
- **Sync/retry is the client's job, on the host.** The base protocol has **no built-in actionability wait** (unlike Playwright); flakiness pushed the ecosystem to client-side explicit waits (`WebDriverWait` + `ExpectedConditions`) and the long-poll `implicitWait`. Appium does NOT depend on a device-pushed idle event; iOS WDA *can* use XCUITest quiescence but it's the same `waitForQuiescence` that Maestro disabled, and Appium exposes `waitForIdleTimeout`/`animationCoolOffTimeout` knobs precisely because it hangs. **Lesson: a mature black-box tool keeps sync host-side and treats the device idle signal as an unreliable, tunable hint — never a hard dependency.**
- **Error model is strings + a `status`/`error` code over the wire** (`no such element`, `stale element reference`, `timeout`). Coarser than the proposal's `category`+`retryable`+`candidates`, and the community routinely complains it's hard to distinguish "wrong locator" from "app said no" — validating the proposal's `candidates[]`.

### Playwright — engine pushes actionability into the locator; auto-wait is host-orchestrated over DOM events
- **Surface/engine split:** `Page`/`Locator` API (the surface) over the CDP-driven browser engine. Locators are **lazy and re-resolved on use** (the proposal's `ref`/re-resolve rule), and every action runs **actionability checks** (visible, stable, enabled, receives events, not obscured) with auto-wait/retry built into the *action*, not the test. Retry/timeout are **engine policy**, not per-test boilerplate — the opposite of Appium and of Maestro-today's per-consumer `onCommandFailed`.
- **Sync mechanism:** the host orchestrates waits, but it is fed by the browser's rich, *reliable* event/mutation signals over CDP (a true bidirectional channel). The browser is a far more cooperative target than a black-box mobile app: there is a real DOM, real mutation observers, real "navigation/network idle" events. **This is the environment in which "host-side event-driven sync" genuinely works — and it is the environment Maestro does NOT have.**
- **Trace** = a single `trace.zip` (NDJSON model + content-addressed artifacts), one stream for live viewer and disk — exactly the proposal's Dimension 4.

### Espresso / UIAutomator (Android) and XCUITest (iOS) — on-device, gray-box, real synchronization
- **Espresso** runs **in-process** and synchronizes against the app's `Looper`/`MessageQueue` + `IdlingResource`s — a *true* idle signal because it lives inside the app. This is the principled "event-driven sync" the proposal romanticizes, but it is **gray-box**: it requires linking into the app you built. EarlGrey 2 (`research/ios-snapshot-levers.md` §6) is the iOS analog. **The reason Maestro is poll-and-diff and not Espresso-style is the entire reason Maestro exists: black-box, any app, no SDK.** You cannot get Espresso-quality idle signals without giving up the black-box promise.
- **XCUITest** itself is host-side (test process) talking to the app via XPC snapshots + `waitForQuiescence` — and its quiescence is the very signal Maestro disabled (#2332).

**Synthesis:** every mature framework keeps the *surface* as a thin adapter over a typed engine
(Appium protocol, Playwright Locator, XCTest queries) — the proposal's L1 is well-precedented and
correct. But on sync/retry there is a hard fork by *box model*: **black-box tools (Appium, Maestro)
keep sync host-side and treat device idle as an unreliable hint; only in-process gray-box tools
(Espresso/EarlGrey) and cooperative targets (Playwright/browser) get reliable event-driven idle.**
The proposal's L3 borrows Playwright's *actionability* model (good, host-computable from any
snapshot) but also borrows Playwright's *device-pushed idle* (bad — Maestro's targets don't
reliably provide it).

---

## PART 3 — Honest Critique of the Layering

### 3.1 L1: Does the adapter/surface model actually keep surfaces swappable, or will MCP/agent needs leak back into the IR/engine?

**Mostly yes on swappability — the typed IR already exists and is already the de-facto API** (`MaestroCommand` is what all four consumers manipulate). Promoting it to a versioned public `Command` with YAML as one `parse(yaml)->Flow` adapter is a *surfacing* exercise, low-risk, and would immediately delete the duplicated callback wiring across `MaestroCommandRunner`, `TestSuiteInteractor`, and `McpViewerOrchestra`. The single highest-value L1 move is a **Core facade that returns the span/event stream as a value**, so consumers stop hand-rolling `IdentityHashMap` trace reconstruction and per-surface `onCommandFailed` retry policy.

**But three concrete leaks are visible in the code today, and the proposal underplays them:**

1. **The "observation projection" is an MCP/agent concept that has already leaked into a surface, not the core.** `inspect_screen` ships a bespoke `ViewHierarchyFormatters.extractCompactJsonOutput` with a hand-tuned abbreviation schema (`b`=bounds, `txt`=text, `rid`=resource-id, `a11y`=…) and a 400-word tool description full of agent-specific caveats ("never author text from a screenshot", "text: is full-string regex"). The proposal puts the dense LLM projection *in the core*. That is the right call — but it means the **core now owns an agent-facing rendering format**, which is an MCP need bleeding into the core's observation contract. Whether that's "leakage" or "correct consolidation" depends on a product decision the proposal asserts but doesn't defend: that the LLM projection is a first-class core output, not a surface concern. If a second agent surface wants a *different* projection (e.g. screenshot-anchored coords for a vision model), the core's single projection is now a constraint.

2. **`ref` binding makes the engine stateful across calls in a way YAML never required.** The proposal's `observe()->{ref:"e3"}->run(tapOn ref)` loop (the whole agent value-prop) requires the engine to retain an **observation generation** and re-resolve `ref` at act-time (the staleness problem `research/observation-hierarchy.md` §2.6 flags). YAML/CLI/batch consumers never need this. So the engine grows a session/observation-cache lifecycle *purely* for the agent surface — an agent need shaping the engine's state model. It's defensible, but it is exactly "MCP/agent needs leaking back into the engine," and it should be designed as an explicit `Observation` object with a generation id, not bolted onto a stateless runner.

3. **Per-surface error-resolution policy is currently a feature, and the core can't fully absorb it.** Today each consumer decides in `onCommandFailed` whether to FAIL or CONTINUE and whether to retry. The proposal moves retry into the engine keyed on `retryable`. Good — but `continueOnFailure`, optional-command-as-warning, and `RetryCommand`/`RepeatCommand` semantics (Orchestra.kt:795-860, `MAX_RETRIES_ALLOWED = 3`) are flow-authoring concepts that some surfaces want and others (an interactive agent doing one command at a time) do not. The core must expose retry as *policy the surface configures*, or it will either over-retry for agents (masking the very `candidates` signal the agent wants) or under-retry for CI. The proposal's `retryable` field is necessary but **not sufficient**; the *policy* (how many times, backoff, whether at all) is still a surface concern that must be a first-class core input, not hard-coded.

### 3.2 L3: Is host-side event-driven synchronization (device-pushed idle/changed) feasible and clean given platform realities?

**This is the weakest part of the proposal, and the codebase + PR history give specific, damning evidence.**

**(a) Neither transport can push today.** `maestro_android.proto` defines `service MaestroDriver`
with **all-unary RPCs** (`returns (X)`), the only stream being client→device `addMedia`. There is
**no server-streaming or bidirectional RPC** — the device cannot push to the host. iOS is worse:
the on-device runner is a **`FlyingFox` HTTP server** (`XCTestHTTPServer.swift`) that the host
*queries* — pure request/response, no push channel at all. So "device pushes `device.idle`"
requires building a **new bidirectional transport on both platforms** (gRPC server-streaming +
a persistent socket/long-poll on iOS, plus the on-device detection logic). This is a genuine
rebuild — the comparison doc (Dimension 5/8) correctly calls it A's single most expensive item.

**(b) Even with a push channel, the device's native idle signal is known-unreliable — Maestro
already removed it.** PR **#2332** disabled XCTest quiescence because Apple's idle notification
hangs 60+ seconds on screens with perpetual animations/spinners/background work ("App animations
complete notification not received"). PR **#953** caps Android's `isWindowUpdating` at 1s because
it over-reports busy on screens with timers/videos. So the two device-native "is it idle" signals
that *do* exist are precisely the ones that **false-never-idle (iOS)** and **false-busy (Android)**.
The proposal's NDJSON example shows a clean `device.idle {pushed:true}` arriving 235ms after a tap;
the production reality is that signal either never arrives (iOS animation) or arrives wrong
(Android filler). A host that *blocks on* a pushed idle event will hang exactly where #2332 hung.

**(c) The proposal's own trace example quietly assumes the unreliable signal is reliable.** In the
worked example, the `wait` span `s3` ends `ok` because a `device.idle {pushed:true}` arrives. There
is no modeled path for "idle never arrives" — which is *the* common case Maestro engineered around.
A correct design must treat the pushed event as a **best-effort hint that races against a hard
timeout and a cheap fallback diff**, i.e. the hybrid that `research/ios-snapshot-levers.md` §4
recommends and that iOS settle (#734/#787) and Android settle (#953) already partially implement.
The proposal frames event-driven sync as a *replacement* for poll-and-diff; the evidence says it
can only be a *primary-with-poll-fallback*, which is a meaningfully smaller and less clean claim.

**(d) Actionability (wait-until-hittable) is the genuinely portable, host-computable part — and the
proposal should lead with it.** Computing `hittable`/`enabled`/`visible` from a snapshot and waiting
for it is sound, platform-agnostic, and needs *no* new transport (it's just smarter use of the
existing pull). The mistake is bundling that (cheap, safe, high-value) with device-pushed idle
(expensive, risky, platform-fragile) under one "event-driven sync" banner. **Unbundle: ship
actionability-gated waits over the existing pull transport now; treat device-push as a later,
optional, fallback-guarded optimization.**

### 3.3 Where the layering is naive

- **"Surfaces are swappable adapters" understates that today they are swappable by *copy-paste*.**
  The proposal presents adapter-ness as an architectural property to *preserve*; in reality it must
  be *created*. There is no Core API package; `maestro-orchestra` + `maestro-client` ARE the API,
  and consumers couple to their internals (callbacks, `IdentityHashMap`, `FlowDebugOutput`,
  `CompositeCommand`). The first concrete deliverable should be a narrow `maestro-core` module that
  the others depend on *instead of* reaching into orchestra — otherwise "adapters" is aspirational.

- **The trace-as-single-stream claim collides with `adjustedToLatestInteraction` and per-surface
  state.** The proposal's span stream assumes the engine is the sole owner of timing/retry. But
  `adjustedToLatestInteraction` (PR #388) means *timeout budgets depend on wall-clock since the last
  mutation* — a hidden global (`timeMsOfLastInteraction`) that is not in any command and not in the
  trace. If the engine emits spans but keeps this implicit global, the trace will show waits whose
  durations can't be explained from the spans alone. Either lift it into the trace as an explicit
  attribute or drop it.

- **iOS/Android sync divergence is treated as an implementation detail; it's a contract risk.**
  The proposal's `Element.hittable` and `device.idle` are presented as uniform. In code, iOS infers
  "static" via *screenshot pixel-diff* and Android via *`isWindowUpdating` + hierarchy-diff* — these
  produce *different* notions of "settled" (pixels vs. accessibility tree). A cross-platform
  `device.idle` event that means "pixels stopped" on iOS but "window-update bit cleared" on Android
  is a leaky abstraction that will make the same flow flake differently per platform. The contract
  must define `idle`/`settled` *semantically* and accept that each platform approximates it
  differently — or it repeats the merged-vs-unmerged silent-divergence trap the observation research
  flags.

- **The cloud worker (out-of-tree) is the consumer most punished by today's coupling and least
  visible to this proposal.** If the cloud worker embeds `maestro-orchestra` directly, every engine
  refactor is a cross-repo migration. The Core facade's biggest payoff is precisely there, but the
  proposal scopes only the OSS surfaces.

---

## PART 4 — Recommendation

**Adopt the L1 adapter framing and the L3 typed-IR / errors-as-values / span-trace pieces; ship them
over today's pull transport; explicitly DECOUPLE and DOWNGRADE "device-pushed event-driven sync."**

Ordered, by value-over-risk:

1. **Build a real Core seam (the missing L1 boundary).** Extract a `maestro-core` API:
   `run(Command) -> CommandResult` (value, never throws across the boundary), `observe(query) ->
   Observation`, and a `trace: Flow<TraceEvent>` the engine *owns*. Make CLI/MCP/Studio-desktop/cloud
   depend on it instead of reconstructing callbacks. This deletes the duplicated `IdentityHashMap`/
   `FlowDebugOutput` wiring in `MaestroCommandRunner`, `TestSuiteInteractor`, and `McpViewerOrchestra`.
   The typed IR already exists, so this is surfacing + a facade, not a rewrite.

2. **Fix the dual error channel (highest value, lowest cost, owed regardless).** Fold
   `DeviceUnreachableException` and JS-eval errors into the result type as values with
   `category ∈ {author,test,app,infra}` + `retryable` + stable `code` + `candidates[]`. Today
   `if (e !is MaestroException) throw e` silently crashes consumers on the most-retryable failures.
   `MaestroException`'s leaf types map cleanly onto categories (`AppCrash`→app,
   `ElementNotFound`/`AssertionFailure`→test, `UnableToLaunch`/`DriverTimeout`/`DeviceUnreachable`→infra).

3. **Make retry *policy* a configured core input, not per-surface `onCommandFailed` and not a single
   `retryable` bool.** Keep `retryable` as the engine's "is this safe to retry" fact, but let the
   surface supply the policy (count/backoff/whether-at-all). Agents want *no* auto-retry on `test`
   failures (it masks `candidates`); CI wants bounded retry on `infra`. Preserve `RetryCommand`/
   `RepeatCommand` as flow-level constructs distinct from infra retry.

4. **Ship actionability-gated waits over the EXISTING pull transport.** Compute
   `hittable/enabled/visible` from the snapshot and wait-until-actionable before acting. This is the
   safe, portable 80% of "event-driven sync" and needs no new transport. Reuse the cheap idle
   detectors already present: iOS screenshot-diff (#734/#787), Android `isWindowUpdating` (#953).

5. **Demote device-pushed idle to a later, optional, fallback-guarded optimization — and never let
   the engine *block* on it.** A pushed `idle`/`changed` event is a *hint that races a hard timeout
   and a cheap diff*, exactly because #2332/#953 proved native idle is unreliable. Design the trace's
   `wait` span to model "idle never arrived → fell back to diff → timed out" as first-class outcomes,
   not just the happy `device.idle{pushed:true}` path. Defer the bidirectional-transport rebuild
   (gRPC server-streaming + iOS persistent socket) until after 1–4 ship value.

6. **Consciously re-derive `adjustedToLatestInteraction`.** It is a poll-and-diff-era heuristic
   (PR #388) that subtracts elapsed wall-clock from lookup budgets. In an actionability/event world
   it is at best meaningless and at worst flake-inducing. Either lift it into the trace as an explicit
   attribute and keep it deliberately, or drop it — do not port it silently.

7. **Define `idle`/`settled` and the LLM projection semantically in the contract**, accepting that
   iOS (pixels) and Android (window/tree) approximate them differently, so the same flow doesn't
   flake per-platform. Treat the agent projection as a core output but version it, anticipating a
   second (vision-anchored) projection.

**Bottom line:** the surface-adapter model is correct and largely latent in the code; the typed-IR,
errors-as-values, and span-trace are correct and cheap (and fix real defects visible today). The one
claim that does not survive the codebase + PR history is **host-side synchronization that depends on
device-pushed idle/changed signals** — Maestro already tried the native idle signal on both platforms
and removed it (iOS #2332 / Android #953 cap) because it is unreliable, and neither transport can push
today. Keep the *actionability* half of event-driven sync (portable, cheap, real); treat the
*device-push idle* half as a fallback-guarded optimization, sequenced last.
