# Architecture Review — Trace / Observability / Artifacts Layer

**Reviewer:** senior architecture review (Claude)
**Date:** 2026-05-28
**Subject:** The span-tree trace + content-addressed artifacts + unified raw-log subscription
proposal in `worked-example-core-contract.md` (Dimension 4) and `research/artifacts-and-logging.md`.

This review does NOT re-derive the artifact taxonomy or clock-sync argument — `research/artifacts-and-logging.md`
already does that well and I concur with its §8–§12 conclusions. This review focuses on the **trace
model itself** (the span tree, intermediate events, one-stream-for-live-and-disk thesis) and grounds the
cost-benefit verdict in (a) what Maestro actually does today on each platform, with PR rationale, and
(b) what the comparable frameworks converged on. The new contribution here is the honest critique of the
span-tree's emission cost and the "one NDJSON stream" claim at scale, plus a concrete mapping of how the
**already-in-flight** `feat/orchestra-artifacts-refactor` branch relates to this proposal.

---

## 0. Verdict up front

- **The artifact half (content-addressed store + handles + clock-sync + raw-payload-with-envelope) is unambiguously correct and under-built today.** Build it. The research doc nails the layering. No notes beyond §6 below.
- **The trace half (span tree + intermediate events as one append-only stream) is correct in *shape* but is the only place the proposal risks over-engineering.** The span *tree* (run→flow→command) earns its keep and is what Maestro is *already converging toward* on a live branch. The sub-command spans (`retry`, `deviceAction`, `wait`) and especially the high-frequency `SpanEvent` poll-tick stream are where cost can exceed value — they must be **opt-in / sampled / capability-gated**, not always-on.
- **"One NDJSON stream serves live + disk" is realistic for the span lifecycle events, NOT for the firehose** (poll ticks, raw device logs, large artifacts). The single-stream invariant must hold for the *structured span events* (low volume, high value, drift-prone if duplicated) and must explicitly **exclude** blobs (already handled — by-handle) and **decouple** high-frequency point events behind backpressure. The §6 single biggest weakness expands this.

---

## 1. Maestro today — what's captured and how, iOS vs Android

### 1.1 The trace model today: `FlowDebugOutput` + per-consumer reconstruction

The "trace" today is `FlowDebugOutput`
(`maestro-orchestra/src/main/kotlin/maestro/orchestra/debug/FlowDebugOutput.kt`):

```
FlowDebugOutput(
  commands: IdentityHashMap<MaestroCommand, CommandDebugMetadata>,  // status, timestamp(ms), duration, error, hierarchy, sequenceNumber, evaluatedCommand
  screenshots: MutableList<Screenshot{file, timestamp, status}>,
  exception: MaestroException?,
)
```

It is **flat, not a tree**. Nesting (composite commands: `runFlow`, `repeat`, `retry`) is *not* represented
in `FlowDebugOutput` at all — it is reconstructed *per consumer* by walking `CompositeCommand.subCommands()`
at render time. `CommandStatus` is a flat enum: `PENDING, RUNNING, COMPLETED, FAILED, WARNED, SKIPPED`
(`CommandStatus.kt`).

The load-bearing mechanism is `Orchestra`'s lambda callback fan-out
(`Orchestra.kt` ctor, lines 130–138): `onFlowStart`, `onCommandStart`, `onCommandComplete`,
`onCommandFailed`, `onCommandWarned`, `onCommandSkipped`, `onCommandReset`, `onCommandMetadataUpdate`,
`onCommandGeneratedOutput`. Orchestra holds **no persistent trace** beyond a
`mutableMapOf<MaestroCommand, CommandMetadata>` (`rawCommandToMetadata`, line 162) used to accumulate
metadata between callbacks. The "trace" exists only in whatever each consumer chooses to build from the
callbacks.

**Each consumer re-implements the reconstruction, with subtle divergence.** This is the single most
important finding for validating the proposal — there are THREE independent reconstructions today:

1. **`MaestroCommandRunner`** (`maestro-cli/.../runner/MaestroCommandRunner.kt`): keeps its own
   `IdentityHashMap<MaestroCommand, CommandStatus>` **and** `IdentityHashMap<MaestroCommand, CommandMetadata>`,
   plus mutates the shared `FlowDebugOutput`. On *every* callback it calls `refreshUi()`, which calls
   `toCommandStates(...)` — a recursive walk that rebuilds the **entire** `CommandState` tree from scratch
   (`toCommandStates`, lines 205–233) and hands a full snapshot to the `ResultView`. The `AnsiResultView`
   then re-renders the whole frame (`renderRunningState`/`renderCommands`). So "collapse on read" already
   happens — but eagerly, on a full-tree snapshot, O(commands) per event.

2. **`TestSuiteInteractor`** (`maestro-cli/.../runner/TestSuiteInteractor.kt`, lines 186–245): a *separate*
   set of inline lambdas that mutate a *fresh* `FlowDebugOutput`, with its own `commandSequenceNumber`
   counter, its own screenshot-on-failure call, and its own `CommandDebugMetadata` seeding. Near-duplicate
   of (1) but not shared.

3. **`McpViewerOrchestra`** (`maestro-cli/.../mcp/viewer/McpViewerOrchestra.kt`): a *third*
   `IdentityHashMap<MaestroCommand, Int>` keyed reconstruction that `seed()`s the full tree (incl. nested)
   at `onFlowStart`, then flips statuses, then publishes a full `FlowState` snapshot over SSE on each event.
   It even has its **own divergent `CommandStatus` enum** — note `STARTED` instead of `RUNNING`, and it
   folds `WARNED` into `SKIPPED` because "from the user's perspective they're just expected misses." It also
   re-implements trailing-PENDING sweep on failure (lines 81–84). This is a literal, in-tree demonstration
   of the drift the proposal exists to prevent.

**This is the core problem the proposal correctly identifies.** Three consumers, three reconstructions,
three slightly different status vocabularies, three screenshot policies. The `IdentityHashMap`-keyed-by-
`MaestroCommand`-reference design *forces* this: the trace is never materialized as data, so it must be
re-derived everywhere, and there is no single artifact a post-mortem tool, the cloud, and an AI eval can all
read. The proposal's "one append-only event stream, consumers collapse on read" is the direct, correct
answer to exactly this.

The on-disk serialization (`TestOutputWriter` + `TestDebugReporter`) is a *fourth* path: it walks the
`IdentityHashMap` and writes `commands-(<flow>).json`, `screenshot-<emoji>-<ts>-(<flow>).png`, and
`maestro.log` (Maestro's own JVM logs, not device logs) into
`$XDG_STATE_HOME/maestro/tests/<ts>/`, 14-day purge. This disk format is *derived from the same map* the live
UI consumes — but via a different code path, so it can and does drift from what the live UI showed (e.g.
sequenceNumber ordering vs. render ordering).

### 1.2 Insights system

`Insights` (`maestro-utils/.../Insights.kt`) is a tiny observer: `report(Insight)`,
`onInsightsUpdated(callback)`, `unregisterListener(callback)`. `Insight = {message, level: WARNING|INFO|NONE}`.
`CliInsights` is a process-global singleton holding a single `var insight` and a listener list.
In `Orchestra.executeCommands` (lines 261–300), per command a callback is registered that copies the latest
insight into that command's `CommandMetadata.insight`, then unregistered in `finally`. Insights are also how
`CommandWarned` and retry-attempt messages surface (lines 284, 854).

This is **platform-agnostic** — the same on iOS and Android — and is *not* part of the structured trace; it
is a side-channel that gets snapshotted into per-command metadata. It is essentially a degenerate, single-slot
version of the proposal's `SpanEvent` point-in-time markers, with no timeline and no nesting.

### 1.3 Artifact handling — and where iOS and Android diverge

`Driver` (`maestro-client/.../Driver.kt`) artifact surface is tiny and **write-only**:
`takeScreenshot(out: Sink, compressed)`, `startScreenRecording(out: Sink): ScreenRecording`,
`contentDescriptor(): TreeNode`, `waitForAppToSettle(): ViewHierarchy?`. `ScreenRecording` is literally
`interface ScreenRecording : AutoCloseable` (`ScreenRecording.kt`) — **no metadata, no start time, no fps**.
There is **no** `Driver` method for device logs, crash reports, bugreports, or metrics. The driver never owns
or catalogs what it produced; bytes flow into a caller-supplied `Sink` and the driver forgets them.

| Concern | iOS | Android |
|---|---|---|
| Screenshot | `LocalSimulatorUtils.screenshot()` → `xcrun simctl io <id> screenshot` (PNG, host-side process) | on-device gRPC `screenshot()` to the instrumentation (`AndroidDriver.takeScreenshot`, line 527), returns bytes over the wire |
| Screen recording | `LocalSimulatorUtils.startScreenRecording` → bundled `screenrecord.sh` running `simctl io recordVideo` as a host `ProcessBuilder`; `.mov` h264 (`LocalSimulatorUtils.kt` ~647) | `AndroidDriver.startScreenRecording` (line 538) shells on-device `screenrecord --bit-rate 100000` (very low), `--time-limit 0` only if API≥34, async; on `close()` does `killall -INT screenrecord`, **sleeps 3s**, then `dadb.pull` |
| Recording metadata | none | none — no start wall-clock, no fps, no offset |
| Device logs (logcat/OSLog) | **none collected** (driver shells simctl for screenshots/video only) | **none collected** host-side; `MaestroDriverService` only *writes* its own `Log.d` |
| Crash / ANR / tombstone / `.ips` | **not collected** — only a *string* in `XCTestIOSDevice.kt` line 233–236 telling the user to "check ~/Library/Logs/DiagnosticReports" on `AppCrash`. Maestro points at the crash dir; never reads it. | **not collected** — no tombstone/bugreport/ANR capture; `BugReportCommand` is a `TODO("Not yet implemented")` stub |
| Timestamp domain | host wall clock (`System.currentTimeMillis()`) | host wall clock |

The platform asymmetry matters for the proposal: an **iOS screenshot is captured host-side** (simctl, the
host already has the bytes and the host clock), whereas an **Android screenshot crosses the gRPC wire from
the device**. A content-addressed store with clock-sync must therefore stamp the iOS artifact with the host
clock directly but reconcile the Android artifact's *capture* time on-device against the host timeline — the
exact problem `research/artifacts-and-logging.md §8` addresses. The recording divergence is worse: Android's
`close()` has a hard-coded 3s sleep and the pre-API-34 3-min cap, so Android recordings are *time-ranged with
a fuzzy stop boundary*, while iOS recordings stop on process kill. Any "video as time-range artifact" model
must carry per-segment start/stop and tolerate the Android stop fuzz.

### 1.4 Reporters

`JUnitTestSuiteReporter` emits standard `<testsuites>/<testsuite>/<testcase>` + `failure` — **no attachment
mechanism** (the JUnit-XML standard's known gap). `HtmlTestSuiteReporter` + `HtmlAITestSuiteReporter`.
`ReportFormat` = JUNIT/HTML/NOOP. `captureSteps` in `TestSuiteInteractor` (line 276) re-derives a flat step
list from the `IdentityHashMap` sorted by `sequenceNumber` — a *fifth* consumer of the same untyped map.

---

## 2. PR archaeology on the load-bearing choices

### 2.1 The debug-output model — PR #988 "Enhanced debug output" (May 2023, dbb666ee)

**Why added:** introduced the per-session debug folder, `commands-*.json`, the 14-day purge, the
`DebugLogStore`/log4j `maestro.log` capture, and cloud/JUnit failure-reason plumbing — all at once.
**Validation:** the *folder + JSON + log* idea is sound and survives. But the data model it shipped
(`IdentityHashMap<MaestroCommand, CommandDebugMetadata>` keyed by command *reference*) is the root cause of
the per-consumer reconstruction sprawl in §1.1. Keying by object identity means the "trace" can only be
read by code that still holds the original `MaestroCommand` references — it is not a portable artifact, it is
an in-memory side-table. **Not a legacy endorsement:** this choice should be retired by the proposal. The
identity-map was expedient in 2023 (one consumer, the CLI) and has aged badly as consumers multiplied
(cloud, MCP viewer, AI eval).

### 2.2 The insights system — PR #1274 "Raising insights on view hierarchy" (Aug 2023, b9c327ad), revised by PR #2131 "use noop insights by default" (Nov 2024, 17e67dce)

**Why added (#1274):** to surface hierarchy-derived hints (e.g. "element exists but is not visible") into the
CLI result view *and* Maestro Studio (the PR touches `InsightService.kt`, the web `api.ts`, `Header.tsx`).
**Why revised (#2131):** the original made `CliInsights` the default everywhere, including library/embedded
usage, which leaked process-global mutable state and listener registration into contexts that didn't want it;
the fix made `NoopInsights` the default and `CliInsights` opt-in. **Validation:** the *intent* (a
point-in-time, leveled diagnostic attached to the active command) is exactly a `SpanEvent` — and the #2131
fix is itself evidence that a **process-global single-slot mutable insights sink was the wrong shape**. The
proposal's per-span `SpanEvent` stream subsumes insights cleanly and removes the global-singleton smell. Keep
the concept, drop the singleton.

### 2.3 Screenshot-on-failure — origin in PR #988 / #1946 ("optional" field) era, now actively being relocated

Screenshot-on-failure today lives in **each consumer's** `onCommandFailed` lambda calling
`ScreenshotUtils.takeDebugScreenshot(maestro, debugOutput, FAILED)` (e.g. `MaestroCommandRunner.kt` line 142,
`TestSuiteInteractor.kt` line 214). So the *policy* ("snapshot on first failure") is duplicated per consumer
and can diverge (the analyze path also screenshots on PENDING/COMPLETED, line 102/126). **Validation:** this
is duplicated cross-cutting behavior that belongs in the core, not in consumers — precisely what the active
refactor branch (§2.4) moves into an internal `ArtifactsGenerator`. The proposal's "the trace references
artifacts by handle, and the core decides capture policy" is the correct end state.

### 2.4 ACTIVE, DIRECTLY-RELEVANT WORK: `feat/orchestra-artifacts-refactor` (May 2026)

This branch is the proposal's near-neighbor and **must be reconciled with it**. Commits (by amanjeetsingh150,
reviewed by @steviec):

- `a687f3d5` **Phase 0**: adds `OrchestraListener` interface (`onFlowStart`, `onCommandStart(cmd, sequenceNumber)`,
  `onCommandFinished(cmd, outcome, startedAt, finishedAt)`, `onCommandReset`, `onCommandMetadataUpdate`,
  `onFlowEnd`), a `CommandOutcome` sealed type (`Completed/Skipped/Warned/Failed(error)`), an internal
  `ArtifactsGenerator` that always runs and produces the canonical bundle (`maestro.log` via `ScopedLogCapture`,
  `commands.json` at `onFlowEnd`, `screenshot-❌-<ts>.png` on first failure), and a `ScopedLogCapture` (per-flow
  log4j2 FileAppender with a maestro-only filter). Lambdas still fire alongside listeners for back-compat.
- `9c93d9dd` keys command start times by **sequenceNumber, not MaestroCommand** — i.e. actively moving *away*
  from identity-keying. Direct corroboration of §2.1.
- `ebccb9b0` **drops the mutable `debugOutput` field**, makes `runFlow` return
  `FlowResult(success, debugOutput)`. The commit message (quoting @steviec) is the thesis in miniature:
  *"exposing debugOutput as a mutable field … conflated the live-observation channel (OrchestraListener) with
  the terminal record."*

**This is the most important archaeological finding.** Maestro is *already* (a) replacing the lambda fan-out
with a single `OrchestraListener` interface, (b) abandoning identity-keying in favor of a monotonic
`sequenceNumber`, (c) separating the **live observation channel** (`OrchestraListener`) from the **terminal
record** (`FlowResult.debugOutput`), and (d) centralizing artifact/screenshot policy in one internal
generator. The proposal is the logical *completion* of this trajectory, not a departure from it. The gap
between the branch and the proposal:

| Dimension | refactor branch (today) | proposal |
|---|---|---|
| Notification | `OrchestraListener` callbacks (in-process, ephemeral) | append-only `TraceEvent` stream (serializable, the disk format too) |
| Nesting | flat `sequenceNumber`; composites still walked by consumers | explicit `parentSpanId` span tree |
| Terminal record | `FlowResult.debugOutput` (still the `IdentityHashMap`-backed `FlowDebugOutput`) | the replayed event stream collapses to the same record |
| Sub-command detail | none (command granularity only) | `retry`/`deviceAction`/`wait` spans + `SpanEvent` ticks |
| Cross-process / AI | not addressed | the NDJSON stream is the interchange format |

**Recommendation:** the proposal should explicitly *land on top of* this branch. `OrchestraListener` becomes
the in-process subscriber to the `TraceEvent` stream; `CommandOutcome` maps to `SpanEnded.status`;
`ArtifactsGenerator` becomes one more subscriber that emits `ArtifactRef` events and writes blobs to the
content-addressed store. Do not re-litigate the listener-vs-lambda decision — it is already made and correct.

---

## 3. What other frameworks / standards capture, and how it's consumed

### 3.1 Playwright trace + trace viewer — the artifact template

A Playwright trace is a single portable `trace.zip` containing a `.trace` file (**newline-delimited JSON, one
event per line** — i.e. NDJSON, exactly the proposal's wire format), a separate `network.trace`, plus resource
files (DOM snapshots, screenshots, screencast frames) referenced by hash. The viewer reconstructs a timeline
with **before / action / after** DOM snapshots per action and lets you scrub. Consumed for: human debugging
(the viewer), CI artifact upload, and increasingly AI/agent post-mortems.

**Lessons the proposal already absorbs:** (1) NDJSON event log as the model; (2) artifacts content-addressed
and referenced, not inlined; (3) one portable bundle. **Lessons the proposal should steal harder:**
(a) Playwright splits the **network** stream into its *own* file (`network.trace`) precisely because it is a
different volume/lifecycle from action events — this validates §0/§6's "don't put the firehose in the span
stream." (b) Playwright's before/after snapshots are *exactly* the proposal's `resolvedCommand` + observation
refs, but anchored as **paired** artifacts around an action; the proposal's per-`SpanEvent` actionability
ticks are a *finer-grained* version that Playwright deliberately does NOT emit — it captures state at action
boundaries, not on every poll. That is a deliberate cost choice (see §5).

### 3.2 OpenTelemetry traces + logs — the envelope and correlation

OTel traces are spans with `SpanId`/`ParentSpanId`/`TraceId`, start/end times, `status`, `attributes`, and
`events` (timestamped point-in-time markers on a span). **This is structurally identical to the proposal's
`Span`** — the proposal is essentially "OTel spans for a test run." OTel logs carry `Timestamp` (origin) vs
`ObservedTimestamp` (collector), `SeverityNumber`+`SeverityText`, `Body`, `Resource` vs `Attributes`, and
`TraceId`/`SpanId` back-references. Consumed by: Jaeger/Tempo/Grafana (human), and programmatic analysis.

**Lessons:** (1) the span+events shape is industry-standard, not novel — low risk. (2) OTel **separates the
log signal from the trace signal** and correlates them by id, rather than interleaving log lines into the span
stream. The proposal should do the same: device logs are the OTel *log* signal (their own subscription /
ndjson per producer), correlated to spans by command/span id and timeline — NOT emitted as `SpanEvent`s.
`research/artifacts-and-logging.md §10` already says this; the worked example's "unified raw-payload log
subscription with clock-sync" is consistent with it. (3) OTel's `SpanEvent` is explicitly for *sparse,
meaningful* markers — its own guidance warns against using span events for high-frequency data (use metrics
instead). That is a direct caution against always-on poll-tick `SpanEvent`s (§5).

### 3.3 Chrome trace-event format (and Perfetto) — the closest analogue to "intermediate events"

The Chrome JSON trace format is the **most direct precedent for the proposal's `SpanStarted`/`SpanEnded`
split.** It uses separate **Begin (`B`)** and **End (`E`)** duration events on a (pid,tid) timeline, which a
consumer pairs into nested spans — *exactly* the proposal's `SpanStarted`/`SpanEnded`. Critically: the format
**requires strict nesting for B/E pairs**; anything that doesn't nest (overlapping, cross-thread) must use
**Async events (`S`/`F` with an `id`)** instead. Perfetto enforces this strictly.

**This is a load-bearing validation AND a caution:**
- *Validation:* emitting paired start/end events and collapsing on read is a proven, decades-old model. The
  proposal is on solid ground. The `parentSpanId` approach is actually *more* robust than Chrome's
  implicit-nesting-by-emission-order because it survives out-of-order delivery and concurrency.
- *Caution:* Chrome/Perfetto learned that you do NOT put high-frequency samples in the duration-event stream —
  counters and samples are separate phases (`C`, `P`) with separate handling, and tooling downsamples. The
  proposal's `SpanEvent` poll-tick stream (`s6`'s three `observe matches:0` ticks in the worked example) is
  the analogue of per-frame samples and should be treated with the same discipline: sampled/bounded/opt-in,
  not unconditionally appended to the one stream.

### 3.4 Appium logs — proof the raw-payload-by-type subscription works cross-platform

Appium: `getLogTypes()` + `getLog(type)` + `mobile: startLogsBroadcast` (WebSocket). Types: `logcat`,
`syslog`, `crashlog`, `bugreport`, `server`, `performance`. Entry: `{timestamp, level, message}` — a thin
OTel-envelope. **Lesson:** typed-by-name, raw-payload, capability-gated log access is a proven cross-platform
API. Validates the research doc's `LogBus.subscribe(filter)` and the raw-payload rule. Appium does **not**
attempt a unified log *schema* — it hands you raw lines tagged by source. The proposal must do the same.

### 3.5 Allure / JUnit — export targets, not the model

Allure: result JSON + first-class attachments (separate files by source filename + MIME) + nested steps —
maps cleanly onto the content-addressed store + span tree. JUnit XML: no attachments, paths-only at best.
**Lesson (already in research §1.6/§7):** these are **export adapters** off the internal model, never the
model. The span tree → Allure step tree is a near-trivial projection; span tree → JUnit is lossy and fine.

### 3.6 Convergence

Playwright, OTel, Chrome/Perfetto, Appium, Allure independently converged on: **structured span/event model +
raw artifacts referenced by handle + normalized log envelope + clock-sync, with separate handling for
high-frequency/firehose signals.** The proposal matches this convergence. The *only* place it goes beyond the
convergence is per-poll-tick `SpanEvent`s in the always-on stream — which every one of those systems
deliberately avoids.

---

## 4. The span-tree + intermediate-events stream: is it worth the emission cost?

**Worth it (the tree, the start/end split, collapse-on-read):** YES.
- The tree replaces three-to-five divergent in-memory reconstructions (§1.1) with one materialized artifact.
  The drift between `MaestroCommandRunner`, `TestSuiteInteractor`, `McpViewerOrchestra`, the disk writer, and
  `captureSteps` is a real, present bug surface (divergent status enums!). One stream kills it.
- The start/end split (`SpanStarted`/`SpanEnded`) is what makes *live* progress possible without polling — a
  consumer renders `running` on `SpanStarted` and updates on `SpanEnded`. This is strictly better than today's
  "mutate a map + re-render the whole frame on every callback" (O(commands) per event in `AnsiResultView`).
- It is the industry-standard shape (OTel, Chrome). Low novelty risk.
- It is the natural completion of the in-flight `OrchestraListener`/`FlowResult` refactor (§2.4).

**Where the cost can exceed the value (over-engineering risk):**
- **`SpanEvent` poll ticks as always-on stream events.** The worked example emits a `SpanEvent` per
  `observe` poll (every ~1s) and per actionability transition. A `waitForIdle` or a long `assertVisible`
  retry loop on a slow CI device can emit hundreds of these per command. Multiply by a flow of 100 commands
  and you have a 10⁴–10⁵-event stream dominated by `matches:0` ticks that no human and few agents will read.
  Every comparable system (OTel, Chrome) treats this class of data as metrics/samples with downsampling, not
  as first-class log events. **Recommendation:** poll-tick `SpanEvent`s should be (a) **off by default**,
  (b) enabled only at a "deep trace" verbosity, and (c) **coalesced** (emit "polled N times, 0 matches,
  first@t0 last@t1" on span end, not one event per poll). Keep the *transition* events (`hittable:false →
  true`, `device.idle pushed`) — those are sparse and high-value — and drop the steady-state ticks.
- **`deviceAction`/`wait` spans for every command.** A bare `tapOn` becoming `command → wait(actionable) →
  deviceAction(tap)` triples the span count. For the failure/eval use case this detail is gold; for the
  90%-of-runs-that-pass case it is noise that costs emission + serialization + storage. **Recommendation:**
  make sub-command spans **conditionally materialized** — always emit `command` spans; emit `wait`/`retry`/
  `deviceAction` children only when (a) deep-trace is on, or (b) the command failed/warned/retried (capture
  the detail exactly when someone will want it). This is the Playwright before/after-snapshot discipline:
  fine detail at boundaries that matter, not uniformly.

**A simpler model that would NOT suffice (why the tree wins over a flat list):** today's flat
`sequenceNumber` list (the refactor branch's current state) cannot represent `retry`-with-N-attempts or
`repeat`-loop iterations without consumers re-deriving structure from `CompositeCommand` walking — which is
the very coupling that forces per-consumer reconstruction. The tree is the minimum structure that lets
`retry`/`repeat`/`runFlow` be *data* rather than *re-derived behavior*. So: **command-level tree = required;
sub-command spans + poll ticks = opt-in.**

**Net:** the span tree at run/flow/command granularity is right-sized and overdue. The `retry`/`wait`/
`deviceAction`/`SpanEvent` layer is right *in design* but must be **gated/sampled/coalesced** or it tips into
over-engineering for the common (passing) case.

---

## 5. "One NDJSON stream serves live + disk" at scale — realistic?

**For the structured span lifecycle events: yes, and it is the whole point.** These are low-volume
(O(commands), ~hundreds–low-thousands per flow), high-value, and the single most drift-prone thing to
duplicate (proven by §1.1). A single append-only `TraceEvent` writer that both tees to subscribers and
appends to disk is realistic and eliminates the live-vs-disk drift Maestro has today (the disk JSON is a
*separate* walk of the map from what the live UI renders). Backpressure on this stream is a non-issue at this
volume.

**For the firehose and blobs: no — and the proposal must say so explicitly.** Three failure modes if "one
stream" is taken literally:

1. **High-frequency `SpanEvent`s (poll ticks)** — see §4. A slow-CI flake-debugging run can produce more
   poll-tick events than span events. If these share the one stream and one disk file, the high-value span
   skeleton drowns in ticks, and a live subscriber (Studio progress bar) that only wants top-level spans now
   has to filter a firehose. **Fix:** poll ticks are sampled/coalesced/opt-in (§4); even when on, consider a
   separate lane (Playwright's `network.trace`-style split).

2. **Device logs (logcat/OSLog) must NOT be span events.** Simulator OSLog spew is famously enormous;
   logcat `-b all` is a firehose. The research doc correctly puts these on a *separate* `LogBus` subscription
   with **push-down filtering** (predicate to the device, not host-side) and persists them as per-producer
   ndjson, optionally sealed into a `.logarchive`/logcat-dump blob at session end. They correlate to spans by
   id+timeline, they do not flow *through* the span stream. The worked example's "unified raw-payload log
   subscription with clock-sync" is consistent with this — but the prose's "ONE append-only event stream that
   is simultaneously the live feed and the on-disk NDJSON" risks being read as "logs go in the span stream
   too." **It must be explicit: the span stream and the log stream are two correlated streams, not one.**

3. **Large artifacts (video, .logarchive, bugreport zip) by handle only.** The proposal already does this
   (`ArtifactRef{spanId, ref, kind}`) — good. The single biggest realism point: **never inline bytes in the
   NDJSON.** A video can be hundreds of MB; a `.logarchive` likewise. The stream carries the *handle* (hash +
   mime + t0/t1 + producer); the bytes live in the content-addressed store. Confirmed correct in the worked
   example. The store also gives free dedup (same screenshot referenced twice = one blob) and is the only
   sane way to ship a trace bundle to the cloud or an AI eval.

**Backpressure / lifecycle concerns to specify:**
- A slow live subscriber (a laggy Studio websocket, a cloud uploader) must not block the test run. The disk
  append is the source of truth; subscribers get a **bounded, lossy-on-overflow** view (drop to "skeleton
  only" under pressure) or read from the tail of the disk file. The run never stalls on a consumer.
- The disk NDJSON should be **append-only and crash-safe** (a killed run still has a valid prefix that
  collapses to "everything up to the crash"). This is a strength of the event-log model over the
  mutate-a-map model: today a hard crash mid-flow can leave the `IdentityHashMap` unwritten; an append log is
  durable incrementally.
- **Replay cost:** "collapse on read" means every consumer replays the whole stream to materialize state.
  For a 10⁵-event deep-trace this is non-trivial. Mitigations: the skeleton (span lifecycle only) is small and
  is what live UIs replay; deep detail is replayed only when a post-mortem tool drills into a specific span
  (seek by `spanId`). Consider periodic *snapshot* records (like Chrome's clock snapshots) so a late joiner
  doesn't replay from t0.

---

## 6. THE SINGLE BIGGEST WEAKNESS

**The proposal's headline "ONE append-only event stream that is simultaneously the live feed and the on-disk
NDJSON" conflates three streams that have fundamentally different volume, lifecycle, and backpressure
profiles, and if implemented literally it will not scale.** There are really three correlated streams:

1. the **span skeleton** (low volume, high value, the genuine one-stream-live-and-disk win),
2. the **high-frequency point-event / poll-tick stream** (must be sampled/coalesced/opt-in — every comparable
   system treats this as metrics, not log events), and
3. the **device-log firehose + large blobs** (must be a separate `LogBus`/content-addressed store with
   push-down filtering and by-handle references — never inlined, never routed through the span stream).

The one-stream framing is *correct and valuable for #1* and is exactly what fixes Maestro's present
three-to-five-way reconstruction drift. But the worked example's prose elides the seam, and the always-on
`SpanEvent` poll ticks in the example actively blur #1 and #2. **Make the seam explicit and capability-gated,
or the high-value span skeleton drowns in ticks/logs, live subscribers are forced to filter a firehose, and
the on-disk file balloons for the 90% of runs that pass uneventfully.** Concretely: keep span-start/end +
artifact-refs + sparse *transition* events in the one always-on NDJSON; gate poll-tick and sub-command
(`wait`/`deviceAction`) detail behind a deep-trace flag with coalescing; route device logs and blobs to their
own correlated channels. This is a framing/scoping fix, not a redesign — the underlying model is sound and is
already where Maestro's in-flight `OrchestraListener`/`FlowResult` refactor is heading.

---

## 7. Recommendation summary

1. **Build the content-addressed artifact store + clock-sync + raw-log envelope/subscription as specified in
   `research/artifacts-and-logging.md`.** No notes; it's correct and the present `Sink`-based, metadata-free,
   write-only, host-clock-only model is the weakest part of Maestro today (no device logs, no crash capture,
   `BugReportCommand` a stub).
2. **Adopt the span tree at run/flow/command granularity as the single materialized trace**, replacing the
   `IdentityHashMap<MaestroCommand,...>` per-consumer reconstructions in `MaestroCommandRunner`,
   `TestSuiteInteractor`, `McpViewerOrchestra`, the disk writer, and `captureSteps`.
3. **Land it on top of `feat/orchestra-artifacts-refactor`, not beside it.** `OrchestraListener` becomes the
   in-process subscriber; `CommandOutcome` → `SpanEnded.status`; `ArtifactsGenerator` → a subscriber emitting
   `ArtifactRef`s. The branch already abandoned identity-keying for `sequenceNumber` and already separated
   live-channel from terminal-record — finish that arc.
4. **Gate the firehose.** Sub-command spans (`wait`/`retry`/`deviceAction`) and `SpanEvent` poll ticks are
   off by default, on under deep-trace, and coalesced on span end. Keep sparse *transition* events always-on.
5. **Make the three streams explicit:** span skeleton (one stream, live+disk) / high-freq events (gated,
   possibly separate lane) / device-log firehose + blobs (separate `LogBus` + content store, by-handle,
   push-down filtered). Never inline bytes; correlate by id + clock-synced timeline.
6. **Subsume insights into `SpanEvent`s** and retire the `CliInsights` process-global singleton (PR #2131
   already proved the global-mutable-slot shape was wrong).
7. **Keep JUnit/Allure/HTML as export adapters** projecting off the span tree + store; never the model.
8. **Specify backpressure + crash-safety:** disk append is the source of truth; live subscribers are
   bounded/lossy and never stall the run; consider snapshot records so late joiners and deep-drill consumers
   don't replay from t0.

---

## Sources

- [Trace viewer — Playwright](https://playwright.dev/docs/trace-viewer)
- [Tracing — Playwright](https://playwright.dev/docs/api/class-tracing)
- [Trace Event Format — Google](https://docs.google.com/document/d/1CvAClvFfyA5R-PhYUmn5OOQtYMH4h6I0nSsKchNAySU)
- [Visualizing external trace formats with Perfetto](https://perfetto.dev/docs/getting-started/other-formats)
- [Trace Event Best Practices — Chromium](https://chromium.googlesource.com/chromium/src/+/HEAD/docs/trace_events.md)
- [Logs Data Model — OpenTelemetry](https://opentelemetry.io/docs/specs/otel/logs/data-model/)
- [Clock synchronization — Perfetto](https://perfetto.dev/docs/concepts/clock-sync)
- [Appium — Get Log / Get Log Types](https://appium.readthedocs.io/en/latest/en/commands/session/logs/get-log-types/)
- [Allure Report docs](https://allurereport.org/docs/)
- Maestro repo: `Orchestra.kt`, `FlowDebugOutput.kt`, `CommandStatus.kt`, `TestOutputWriter.kt`,
  `TestDebugReporter.kt`, `MaestroCommandRunner.kt`, `TestSuiteInteractor.kt`, `McpViewerOrchestra.kt`,
  `Insights.kt`/`CliInsights`, `Driver.kt`, `AndroidDriver.kt`, `LocalSimulatorUtils.kt`, `ScreenRecording.kt`,
  `XCTestIOSDevice.kt`; PRs #988, #1274, #2131, #3188; branch `feat/orchestra-artifacts-refactor`
  (commits a687f3d5, 9c93d9dd, ebccb9b0).
