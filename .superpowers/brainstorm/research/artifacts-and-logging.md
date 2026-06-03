# Artifacts & Logging — Research for the Maestro Control-Plane Rearchitecture

**Research conducted:** 2026-05-28
**Researcher:** Claude (artifacts-and-logging research agent)
**Context:** The new core is a typed "control plane" whose value proposition is a *unified* interface for device interaction. Question under investigation: does a unified interface for device **logging / artifacts** belong in that core too, or is it useless abstraction over raw logs?

**Scope:** This doc covers the broader artifact picture: the full taxonomy, formats, timestamp alignment, test-vs-device differentiation, and the core-API verdict. Live device-log *streaming* (logcat/OSLog) is touched here and could be expanded into its own companion doc.

---

## 0. TL;DR / Verdict

- **Yes, a unified artifact + logging contract belongs in the core — but as two layered concepts, not one blob:**
  1. A **content-addressed artifact store** (opaque blobs: screenshots, video, hierarchy dumps, `.logarchive`, bugreport zips, crash files) referenced *by handle* from the structured trace.
  2. A **normalized log/event envelope + subscription** (`{producer, normalizedTimestamp, deviceTimestamp, level, tag, raw}`) for line-oriented streams (logcat, OSLog), modeled on OpenTelemetry's log data model and exposed like CDP's `Log`/`Tracing` domains.
- **Keep payloads raw. Never lossily reformat.** The value is in the *envelope and the timeline*, not in rewriting Apple's/Google's log text.
- **The genuinely hard, genuinely valuable part is timestamp alignment.** Use Perfetto-style clock snapshots (atomically sample device+host clocks at session start and periodically) so every artifact lands on one host-relative timeline. This is the single biggest reason to centralize: nobody downstream can reconstruct it from raw files alone.
- **The one thing to NOT over-build:** a "universal log format" that parses every log line into typed fields. That *is* useless abstraction — logcat and OSLog don't share a schema below the envelope level, and lossy normalization destroys debuggability.

---

## 1. What Maestro does today (grep findings)

Files inspected: `Driver.kt`, `Orchestra.kt`, `TestDebugReporter.kt`, `TestOutputWriter.kt`, `FlowDebugOutput.kt`, `ScreenRecording.kt`, `LocalSimulatorUtils.kt`, `AndroidDriver.kt`, `MaestroDriverService.kt`, `JUnitTestSuiteReporter.kt`, `ReportFormat.kt`, `BugReportCommand.kt`.

### 1.1 The `Driver` contract (`maestro-client/.../maestro/Driver.kt`)
Artifact-relevant surface is tiny and write-only:
- `takeScreenshot(out: Sink, compressed: Boolean)`
- `startScreenRecording(out: Sink): ScreenRecording` (and `ScreenRecording : AutoCloseable` — no metadata at all, just a closeable)
- `contentDescriptor(...): TreeNode` and `waitForAppToSettle(...): ViewHierarchy?` (view hierarchy)

There is **no** `Driver` method for device logs, crash reports, system metrics, or bugreports. Artifacts are pushed into caller-provided `Sink`s; the driver never *owns* or *catalogs* what it produced. There is no notion of an artifact handle, MIME type, or timestamp on the artifact itself.

### 1.2 Screenshots
- iOS: `LocalSimulatorUtils.screenshot()` → `xcrun simctl io <id> screenshot <path>` (PNG).
- Android: driver `takeScreenshot` via the on-device instrumentation (`ScreenshotService`).
- On-failure capture: `Orchestra.takeScreenshotOnFailure(...)` stores a `FlowDebugOutput.Screenshot(file, timestamp, status)`. **This is the only place a Maestro-captured artifact carries a timestamp + status.** Timestamp is `System.currentTimeMillis()` (host wall clock).

### 1.3 Screen recording — different mechanism per platform, neither carries timing metadata
- iOS: `LocalSimulatorUtils.startScreenRecording()` → `xcrun simctl io <id> recordVideo --codec=h264 --force screenrecording.mov` (a `ProcessBuilder` you later kill).
- Android: `AndroidDriver.startScreenRecording()` (line ~538) shells `screenrecord --bit-rate '100000' /sdcard/maestro-screenrecording.mp4` (adds `--time-limit 0` on API≥34 to escape the cap), runs it async, then on `close()` does `killall -INT screenrecord`, sleeps 3 s, and `dadb.pull`s the file — i.e. the **on-device `screenrecord` binary**, which on API<34 inherits its **~3-minute hard cap** and resolution constraints. (Note: the Android driver uses a very low 100 kbps bit-rate; the generic `RecordCommand` path is separate.)
- Neither records a start wall-clock time, frame-rate, or an offset to align video frames to commands. Recording is a CLI feature (`RecordCommand`) plus a flow command (`StartRecordingCommand`), not a trace-correlated artifact.

### 1.4 View hierarchy
Captured as `TreeNode` and serialized into the per-command debug JSON (`CommandDebugMetadata.hierarchy`). Stored as on-failure/per-command evidence, not as a standalone catalogued artifact.

### 1.5 The structured trace today = `FlowDebugOutput`
```
FlowDebugOutput {
  commands: IdentityHashMap<MaestroCommand, CommandDebugMetadata>  // status, timestamp(ms), duration, error, hierarchy, seqNo
  screenshots: List<Screenshot{file, timestamp, status}>
  exception: MaestroException?
}
```
Written to disk by `TestOutputWriter`/`TestDebugReporter` as:
- `commands-(<flow>).json` — the command-level trace
- `screenshot-<emoji>-<ts>-(<flow>).png` — screenshots named by status+timestamp
- `maestro.log` — Maestro's **own** JVM logs (log4j/slf4j via `DebugLogStore`/`LogConfig`), NOT device logs
- AI defect JSON + an HTML report (`HtmlAITestSuiteReporter`)
Default location: `$XDG_STATE_HOME/maestro/tests/<yyyy-MM-dd_HHmmss>/` (or a custom `--debug-output`). 14-day auto-purge.

### 1.6 Reporters (`maestro-cli/.../report/`)
- `JUnitTestSuiteReporter` — emits standard `<testsuites>/<testsuite>/<testcase>` XML with `failure` elements. **No attachment mechanism** (matches the JUnit-XML standard's known weakness).
- `HtmlTestSuiteReporter`, `HtmlAITestSuiteReporter`. `ReportFormat` enum (JUNIT/HTML/NOOP).

### 1.7 Device logs: the gap
- **Android: no `adb logcat` capture anywhere host-side.** `MaestroDriverService` only *writes* to logcat (its own `Log.d` tags) for self-debugging.
- **iOS: no `log stream` / `log collect` / `.logarchive` capture.** The iOS driver shells `simctl` for screenshots/video/launch but never for logs.
- **Crash/ANR/tombstone/`.ips`: not collected.** The only crash-related code is a *string* in `XCTestIOSDevice.kt` (~line 232) that tells the user to "check logs in `~/Library/Logs/DiagnosticReports`" — i.e. Maestro points at the iOS crash dir but never reads it.
- **`BugReportCommand` is a stub (`TODO("Not yet implemented")`).**
- **System metrics (CPU/mem/battery/network): not collected.**

**Conclusion:** Maestro has a per-command trace + screenshots + optional video, all keyed to *host wall-clock* timestamps, written to a per-session folder. It has essentially **no device-OS-generated artifact capture** and **no artifact catalog / handle abstraction**. The rearchitecture is the right moment to introduce both. The current `Sink`-based, metadata-free, write-only model is exactly what a content-addressed store + envelope would replace.

---

## 2. Full taxonomy of artifacts a device interaction / test run can produce

### A. Test-directed (Maestro decides to produce these)
| Artifact | Trigger | Format today | Natural identity |
| --- | --- | --- | --- |
| Screenshot (on-demand) | `takeScreenshot` command | PNG | point-in-time, host ts |
| Screenshot (on-failure) | command failure | PNG | point-in-time, host ts + status |
| Screen recording / video | `startRecording`/`RecordCommand` | iOS `.mov` (h264/hevc), Android `.mp4` | time *range* |
| View-hierarchy snapshot | per command / on settle | `TreeNode` JSON (AXML-ish / a11y tree) | point-in-time |
| Structured trace | always | `commands-*.json` | the spine |
| Assertion evidence | assertions | embedded in trace + screenshot | point-in-time |
| AI screen analysis | AI flows | JSON + screenshots + HTML | per-screen |
| Maestro's own logs | always | `maestro.log` (log4j) | stream |

### B. Device / OS-generated (the device produces these; Maestro would *collect* them)
| Artifact | Source command | Format | Timestamp semantics |
| --- | --- | --- | --- |
| Android logcat | `adb logcat` | text lines, `-v <format>` | see §3 |
| Android bugreport | `adb bugreport` | zip (txt + dumpsys + logcat + traces) | mixed |
| Android ANR | `/data/anr/traces.txt` (via bugreport) | thread-dump text | wall clock at ANR |
| Android tombstone (native crash) | `/data/tombstones/` | text + `tombstone_NN.pb` (protobuf) | wall clock at crash |
| iOS unified log | `simctl spawn ... log stream/show/collect` | syslog/json/ndjson; `.logarchive` bundle | sub-second + tz, see §4 |
| iOS crash report | `~/Library/Logs/DiagnosticReports` / sim equiv | `.ips` (JSON header line + JSON body) | wall clock |
| System metrics | dumpsys/`top`/`simctl`/Instruments | varies (text/plist/trace) | sampled |
| Instrumentation/JUnit output | test runner | JUnit XML / Allure JSON | per test |
| Perfetto/systrace (Android) | `perfetto`/`atrace` | protobuf trace | multi-clock snapshots |

---

## 3. Android logcat — formats & timestamp semantics (cited)

- `-v` output formats: `brief`, `process`, `tag`, `thread`, `raw`, `time`, `threadtime` (**default**), `long`. You can specify only **one** format but **many** modifiers. [Android logcat docs]
- Format modifiers (the timestamp-critical ones):
  - `epoch` — seconds since 1970-01-01 (the tooling-friendly absolute clock).
  - `monotonic` — CPU seconds since last boot (`CLOCK_MONOTONIC`-ish; survives wall-clock jumps).
  - `usec` — microsecond precision.
  - `UTC` — emit as UTC instead of device-local.
  - `year`, `zone`, `uid`, `descriptive`, `printable`, `color`. [Android logcat docs; Herong Yang]
- `threadtime` line shape: `MM-DD HH:MM:SS.mmm  PID  TID  LEVEL TAG: message`.
  **Gotcha:** default carries **no year** and is in **device-local time** — fragile to correlate. For tooling, capture with `-v epoch` (or `-v UTC,usec,year`).
- Priority/levels: `V D I W E F S` (Verbose→Silent). [Android logcat docs]
- Buffers (`-b`): `main`, `system`, `radio`, `events` (binary system-event buffer), `crash`, plus `all`. Each is an independent circular/ring buffer with its own retention; `main` excludes system+crash. [Android logcat docs]

Sources: [Logcat command-line tool — Android Developers](https://developer.android.com/tools/logcat); [adb logcat -v Log Format Control — Herong Yang](http://www.herongyang.com/Android/Debug-adb-logcat-Command-Log-Format-Control.html).

---

## 4. iOS unified logging (OSLog) + simctl — formats & timestamps (cited)

- Three verbs (all via `xcrun simctl spawn booted log ...`):
  - `log stream` — live tail; supports `--style syslog|json|ndjson|compact`, `--level`, `--predicate '<NSPredicate>'`, `--color none`.
  - `log show` — historical; can target a `.logarchive`.
  - `log collect` — produces a portable **`.logarchive`** (a bundled copy of `/var/db/diagnostics`, designed to be transferable / attachable to bug reports).
- Filtering uses **NSPredicate** strings, e.g. `subsystem CONTAINS[cd] "com.app"` — essential because simulator log streams are *extremely* noisy. [iosdev.recipes; smileykeith]
- Config introspection: `log config --status`; per-subsystem level: `log config --mode "level:debug" --subsystem ...`.
- Timestamps: unified logging carries sub-second precision + timezone; `json`/`ndjson` styles emit machine-parseable records (the right choice for tooling).
- Crash reports: modern `.ips` = a JSON header line followed by a JSON body (exception type, termination reason, thread backtraces, binary images, registers). Symbolication needs dSYMs. [Apple: Interpreting the JSON format of a diagnostic report]

Sources: [os_log and log streaming for iOS — iOS Dev Recipes](https://www.iosdev.recipes/simulator/os_log/); [Silencing iOS simulator log noise — Keith Smiley](https://www.smileykeith.com/2021/11/16/simulator-log-spew/); [Interpreting the JSON format of a diagnostic report — Apple](https://developer.apple.com/documentation/xcode/interpreting-the-json-format-of-a-diagnostic-report); [Analyzing a Crash Report — Apple](https://developer.apple.com/documentation/xcode/analyzing-a-crash-report).

---

## 5. Video recording — formats & limits (cited)

- **iOS:** `xcrun simctl io <id> recordVideo [--codec h264|hevc] [--display] [--mask] <out.mov>`. Default codec is **hevc**; Maestro forces **h264** (`--codec=h264 --force`). Output is a QuickTime `.mov`. Stops on SIGINT/process kill. [Apple Dev Forums; Sarunw; fig.io]
- **Android:** `adb shell screenrecord [--bit-rate N] [--size WxH] [--time-limit S] /sdcard/x.mp4`. Default bitrate 20 Mbps (Maestro's Android driver sets a very low 100 kbps); default size falls back to 1280×720 if the device resolution isn't AVC-supported. **Hard cap historically 180 s (3 min) per invocation; Android 14 / API≥34 supports `--time-limit 0` to remove it (which Maestro uses).** [adbshell.com; Appium Discuss]

**Implication for the contract:** video is a **time-range** artifact, and the Android pre-API-34 3-min cap means a long flow on older devices needs *segmented* recordings stitched on the timeline — another reason the artifact store must carry start/stop timestamps per segment rather than assume one file == whole session.

Sources: [adb shell screenrecord — ADB Shell](https://adbshell.com/commands/adb-shell-screenrecord); [recordVideo — Apple Developer Forums](https://developer.apple.com/forums/thread/105878); [Take a screenshot and record a video in iOS Simulator — Sarunw](https://sarunw.com/posts/take-screenshot-and-record-video-in-ios-simulator/).

---

## 6. Crash / ANR / tombstone formats (cited)

- **Android tombstone (native crash):** `/data/tombstones/tombstone_NN` — signal, register state, backtrace, memory maps; modern Android also emits a **protobuf** `tombstone_NN.pb` alongside the text. Extraction reliably needs root or `adb bugreport`.
- **Android ANR:** thread dumps of all threads at the stall, surfaced via `/data/anr/traces.txt` / bugreport; main-thread blocked ~5 s for input. [Android: ANR; Tombstones]
- **iOS `.ips`:** JSON-format diagnostic report (header line + body). Programmatically parseable; needs dSYM symbolication.

Sources: [Tombstones — AOSP](https://source.android.com/docs/core/tests/debug/native-crash); [ANR — Android Developers](https://developer.android.com/topic/performance/vitals/anr); [Interpreting the JSON format of a diagnostic report — Apple](https://developer.apple.com/documentation/xcode/interpreting-the-json-format-of-a-diagnostic-report).

---

## 7. Prior art for centralizing logs/telemetry/artifacts (cited)

| System | Model | Lesson for Maestro |
| --- | --- | --- |
| **OpenTelemetry Logs** | `Timestamp` (origin clock) **vs** `ObservedTimestamp` (collector clock); `SeverityNumber` (1–24) + `SeverityText`; `Body` (string or structured); `Resource` (shared source identity) vs `Attributes` (per-record); `TraceId`/`SpanId` correlation. ns since epoch. | This **is** the envelope. Steal `Timestamp` vs `ObservedTimestamp` (= device vs host clock) and the `Resource`/`Attributes` split verbatim. Correlate logs↔trace via a Maestro span/command id. |
| **Perfetto / systrace** | **Clock snapshots**: atomically sample multiple clocks (BOOTTIME/MONOTONIC/REALTIME) at known instants; trace processor builds a clock-sync graph and converts any timestamp between domains. Protobuf trace. | The **gold standard for multi-clock alignment**. Adopt the snapshot+graph approach for device-vs-host skew (§8). |
| **Appium** | `getLogTypes` + `getLog(type)` + `mobile: startLogsBroadcast` (WebSocket). Types: `logcat`, `syslog`, `crashlog`, `bugreport`, `server`, `performance`, `safariConsole/Network`. Entries: `{timestamp(ms), level, message}`. | Proves a **typed-by-name, raw-payload** log API works across platforms. Validates "envelope + raw, don't reformat." Their entry shape is a thinner version of the OTel envelope. |
| **Playwright trace** | Single `trace.zip` = `.trace` model (newline-delimited JSON events) + resource files (screenshots, DOM snapshots, network) + screencast. Opened in a trace viewer; before/action/after snapshots; timeline scrub. | The **content-addressed-artifacts-referenced-from-a-trace** pattern, packaged as one portable bundle. Strong template for Maestro's "trace references artifacts by handle." |
| **JUnit XML** | `<testsuites>/<testsuite>/<testcase>` + `failure/error/skipped` + `system-out/err`. **No native attachments.** | Keep as an **export adapter**, not the internal model. Artifacts referenced only as text paths. |
| **Allure** | Result JSON + **first-class attachments** (separate files referenced by source filename + MIME type) + nested steps. | The right export target when you need rich artifacts. Mirrors content-addressed store: model JSON separate from blobs referenced by id+MIME. |
| **Flipper** (deprecated by Meta) | Plugin-based on-device log/telemetry viewer. | Cautionary tale: tightly coupled, plugin-heavy device tooling is hard to sustain. Favor a thin, format-stable contract. |

Sources: [Logs Data Model — OpenTelemetry](https://opentelemetry.io/docs/specs/otel/logs/data-model/); [Clock synchronization — Perfetto](https://perfetto.dev/docs/concepts/clock-sync); [Trace viewer — Playwright](https://playwright.dev/docs/trace-viewer); [JUnit XML format](https://github.com/testmoapp/junitxml); [Allure Report](https://allurereport.org/docs/).

---

## 8. Timestamp alignment — the hard part, addressed concretely

**Problem.** Artifacts originate in three clock domains: (1) host wall clock (Maestro JVM, `System.currentTimeMillis()` — what the trace uses today); (2) device wall clock (`CLOCK_REALTIME`; logcat `-v UTC`, OSLog, crash files) — drifts vs host and can *jump* (NTP, manual set); (3) device monotonic/boot clock (logcat `-v monotonic`, Perfetto) — never jumps but has no absolute meaning. Without reconciliation you cannot place a logcat line next to the screenshot taken "at the same moment."

**Recommended approach — Perfetto-style clock snapshots, anchored to one canonical timeline:**

1. **Canonical timeline = host monotonic** (e.g. `System.nanoTime()`), captured per session. Wall-clock is derived/display-only because it can jump.
2. **At session start, take a clock snapshot.** Issue a cheap device command that returns the device clock and measure host time immediately before/after:
   - Android: `adb shell date +%s%N` (epoch ns) and, separately, `cat /proc/uptime` or a `monotonic` logcat marker.
   - iOS sim: a `log` marker / device date probe.
   Compute `offset = device_clock - host_clock`, correcting for round-trip: assume symmetric latency, `offset ≈ device_t - (host_before + host_after)/2`, and record `rtt = host_after - host_before` as the **uncertainty band**.
3. **Re-snapshot periodically** (e.g. every N seconds and at session end) to fit a drift line (linear interpolation between snapshots), exactly like Perfetto's clock-sync graph. This handles slow drift; detect *jumps* by watching for offset discontinuities larger than rtt.
4. **Prefer monotonic device clocks where available** (logcat `-v monotonic`, Perfetto) and convert to the canonical timeline via the snapshot pair — monotonic→boot→wall is robust against NTP jumps.
5. **Per artifact, store both timestamps + the offset used** (OTel's `Timestamp` vs `ObservedTimestamp`). The trace stores `hostMonotonicNanos` (canonical) + `deviceTimestamp` (raw, as the device reported it) + `offsetApplied` + `uncertaintyMs`. Never overwrite the raw device timestamp — keep it so a human can grep the original log.
6. **Video** is anchored by its **start** snapshot; map frame N to timeline via fps. Android's 3-min segmentation means each segment gets its own start snapshot.

**Why this must live in the core, not downstream:** the offset/rtt/drift data only exists *at capture time*. A `.logarchive` + a folder of PNGs handed to a report tool later has irrecoverably lost the cross-clock relationship. Centralizing capture is the only place alignment is even possible.

Sources: [Clock synchronization — Perfetto](https://perfetto.dev/docs/concepts/clock-sync); [Clock synchronization — Wikipedia](https://en.wikipedia.org/wiki/Clock_synchronization); [Logs Data Model — OpenTelemetry](https://opentelemetry.io/docs/specs/otel/logs/data-model/).

---

## 9. Differentiating TEST-DIRECTED vs DEVICE-GENERATED artifacts

Make `producer` (a.k.a. OTel `Resource`) a **mandatory first-class field** on every artifact/log envelope, with a small closed taxonomy:

- `producer.kind`: `MAESTRO` (test-directed) | `DEVICE` (OS-generated) | `RUNNER` (instrumentation/JUnit) | `MAESTRO_INTERNAL` (Maestro's own logs).
- `producer.source`: e.g. `command:takeScreenshot`, `assert:visible`, `logcat:main`, `oslog`, `crash:tombstone`, `metrics:cpu`.
- `causedBy` (optional): the command/span id that **directly caused** a test-directed artifact (a screenshot the flow took); device-generated artifacts have **no `causedBy`** but are *temporally correlated* to the command active in their time window (computed via §8, not asserted).

This gives a clean, queryable distinction:
- **Test-directed** = `kind==MAESTRO` and (usually) has a `causedBy` command id; deterministic, intentional, belongs *in* the trace as evidence.
- **Device-generated** = `kind==DEVICE`, no `causedBy`; correlated *to* the trace by timeline overlap; belongs in the *artifact store / log stream* and is *referenced* from the trace window.

Practical payoff: a viewer can render test-directed artifacts as trace steps and device logs as a background lane underneath — and "show me the logcat lines during the command that failed" becomes a pure timeline range query.

---

## 10. Proposed contract — critique & refinement of the hypothesis

**The hypothesis (as stated):** content-addressed artifact store referenced from the trace; keep device-log payloads raw but wrap each in a normalized envelope `{source, normalized-timestamp, level, raw}` and expose a unified subscription (like CDP's Log domain), without lossy reformatting.

**Verdict: correct in shape; tighten in four places.**

1. **Split blobs from lines.** The hypothesis conflates two things that want different storage:
   - **Blobs** (screenshots, video, `.logarchive`, bugreport zip, crash files, hierarchy dumps) → **content-addressed store** keyed by hash; trace holds a `ArtifactHandle{id, mime, producer, t0, t1?, bytes, sha256}`. Dedup is free (same screenshot referenced twice = one blob). This is the Playwright/Allure pattern.
   - **Lines** (logcat, OSLog, Maestro's own logs) → **log stream** of envelopes; persisted as ndjson per producer, *also* placeable into the store as a blob at session end.
2. **Enrich the envelope to the OTel shape**, not just `{source, ts, level, raw}`:
   `{ producer{kind,source}, observedTimestamp(host), timestamp(device,raw), level{number,text}, tag, raw, traceCorrelation{commandId?}, offsetApplied, uncertaintyMs }`.
   `level` must keep both a normalized number (OTel 1–24) **and** the original text (`E`, `Error`, `<Fault>`) so filtering works without lying about the source.
3. **Subscription, yes — modeled on CDP `Log`/`Tracing` *and* Appium's typed streams.** `subscribe(filter): Flow<LogEnvelope>` where filter = producer/level/predicate. Crucially, push the device-native predicate down (logcat `-b`/tag, OSLog NSPredicate) so you don't drag the whole firehose across the wire and filter in the JVM. This is where naive "unified" abstractions fail — they collect everything then filter host-side and melt under simulator log spew.
4. **"Without lossy reformatting" is right and should be a hard rule.** Store `raw` verbatim (the exact log line / the exact `.ips`). The envelope is *additive metadata*. Never parse logcat into typed columns in the core — that's the "useless abstraction" failure mode (see §11). Parsing is a *downstream/optional* concern (an enricher), never the storage format.

**Resulting core surface (sketch):**
```
ArtifactStore:
  put(bytes|stream, mime, producer, t0, t1?) -> ArtifactHandle
  get(handle) -> stream ;  open()/list(filter)
Trace (the spine):
  span(commandId, t0, t1, status) ;  attach(handle, causedBy=commandId)
LogBus:
  subscribe(filter) -> Flow<LogEnvelope>      // live
  collect(producer, range) -> ArtifactHandle  // .logarchive / logcat dump at end
ClockSync:
  snapshot() -> {deviceClock, hostMonotonic, rtt}   // §8
  toTimeline(deviceTs, producer) -> hostMonotonicNanos
```
Drivers implement *capability-gated* producers (`capabilities(): [LOGCAT, OSLOG, VIDEO, CRASH, METRICS, ...]`) so platforms that can't do a thing simply don't advertise it — mirroring the existing `Driver.capabilities()` pattern.

---

## 11. Honest verdict: valuable, or useless abstraction?

**Argument that it's useless abstraction:**
- logcat and OSLog have genuinely different schemas; any "unified log" risks lowest-common-denominator mush.
- Engineers debugging a flake usually want the *raw* logcat/`.ips`, opened in their familiar tool, not a reformatted Maestro view.
- A naive unified collector that pulls the whole firehose and filters host-side is slower and noisier than `adb logcat -b main TAG:* | grep`.
- JUnit-XML-style consumers can't even carry the artifacts, so a lot of the richness is invisible to existing CI integrations.

**Argument that it's genuinely valuable (and wins):**
- The value is **not** in reformatting payloads — it's in **(a) the unified timeline** (§8) that *cannot* be reconstructed downstream, **(b) the artifact catalog/handles** so the trace can say "here's the screenshot + the logcat window for the command that failed," and **(c) one capability-gated API** so callers (CLI, cloud, MCP, future viewers) don't each re-implement `simctl`/`adb` plumbing — which today is duplicated and, for device logs, *entirely missing*.
- Every serious comparable (Playwright trace, Allure, OTel, Perfetto, Appium) converged on exactly this layering: **structured model + referenced raw artifacts + normalized envelope + clock-sync**. That convergence is strong evidence it's the right abstraction, not over-engineering.
- The failure mode (lossy reformatting, host-side firehose filtering, a god-format) is **avoidable by design rules** stated above (raw payloads, push-down predicates, additive envelope, no typed-column parsing in core).

**Decision:** Build it, scoped precisely:
- **IN the core:** content-addressed artifact store + handles; the trace as the spine referencing artifacts by handle; the normalized log/event envelope (OTel-shaped); a capability-gated subscription with **push-down** filtering; the clock-sync service producing one timeline; the `producer`/`causedBy` distinction.
- **OUT of the core:** any parsing of log lines into typed fields; any "universal" cross-platform log schema below the envelope; rendering/symbolication (downstream enrichers); export formats (JUnit/Allure as adapters).

The unified *interface* is valuable; a unified *payload format* would be the useless abstraction. Keep the seam exactly there.

---

## 12. Concrete recommendations for the rearchitecture

1. Replace the write-only `Sink`-based artifact methods on `Driver` with **producer methods that return `ArtifactHandle`s** carrying mime + timestamps + producer.
2. Add a **`ClockSync` snapshot at session open + periodic re-snapshot**; stamp every artifact and log line with `(hostMonotonic, rawDeviceTs, offset, uncertainty)`.
3. Introduce **logcat / OSLog capture** as capability-gated producers with push-down predicates; persist as ndjson envelopes and as an end-of-session `.logarchive`/logcat-dump blob.
4. Add **crash/ANR/tombstone/`.ips` collection** at session teardown into the store (handles referenced from the trace if they fall in the run window).
5. Make the **trace the spine** that references all artifacts by handle (Playwright/Allure model); keep `commands-*.json` as today's export but back it with the new model.
6. Implement **JUnit (paths-only) and Allure (first-class attachments) as export adapters**, not the internal model. Finish `BugReportCommand` on top of the new store.
7. Enforce the **design rules**: raw payloads verbatim; additive envelope; push-down filtering; no typed-column parsing in core; capability gating per driver.

---

## Appendix — Sources

- [Logcat command-line tool — Android Developers](https://developer.android.com/tools/logcat)
- [adb logcat -v Log Format Control — Herong Yang](http://www.herongyang.com/Android/Debug-adb-logcat-Command-Log-Format-Control.html)
- [adb shell screenrecord — ADB Shell](https://adbshell.com/commands/adb-shell-screenrecord)
- [os_log and log streaming for iOS — iOS Dev Recipes](https://www.iosdev.recipes/simulator/os_log/)
- [Silencing iOS simulator log noise — Keith Smiley](https://www.smileykeith.com/2021/11/16/simulator-log-spew/)
- [xcrun simctl io recordVideo — Apple Developer Forums](https://developer.apple.com/forums/thread/105878)
- [Take a screenshot and record a video in iOS Simulator — Sarunw](https://sarunw.com/posts/take-screenshot-and-record-video-in-ios-simulator/)
- [Interpreting the JSON format of a diagnostic report — Apple](https://developer.apple.com/documentation/xcode/interpreting-the-json-format-of-a-diagnostic-report)
- [Analyzing a Crash Report — Apple](https://developer.apple.com/documentation/xcode/analyzing-a-crash-report)
- [Tombstones — Android Open Source Project](https://source.android.com/docs/core/tests/debug/native-crash)
- [ANR — Android Developers](https://developer.android.com/topic/performance/vitals/anr)
- [Logs Data Model — OpenTelemetry](https://opentelemetry.io/docs/specs/otel/logs/data-model/)
- [OpenTelemetry Logging spec](https://opentelemetry.io/docs/specs/otel/logs/)
- [Clock synchronization — Perfetto](https://perfetto.dev/docs/concepts/clock-sync)
- [Clock synchronization — Wikipedia](https://en.wikipedia.org/wiki/Clock_synchronization)
- [Trace viewer — Playwright](https://playwright.dev/docs/trace-viewer)
- [JUnit XML format — testmoapp/junitxml](https://github.com/testmoapp/junitxml)
- [Allure Report docs](https://allurereport.org/docs/)
- [Appium — Get Log Types / Get Log](https://appium.readthedocs.io/en/latest/en/commands/session/logs/get-log-types/) and [Using Mobile Execution Commands to stream device logs — Appium Pro](https://appiumpro.com/editions/55-using-mobile-execution-commands-to-continuously-stream-device-logs-with-appium)
- [Native crash postmortems via Android tombstones — Sentry](https://blog.sentry.io/native-crash-postmortems-via-android-tombstones/)
- Maestro repo (grep + read): `maestro-client/.../Driver.kt`, `maestro-orchestra/.../Orchestra.kt` (StartRecording/StopRecording, onCommandFailed), `maestro-cli/.../report/TestDebugReporter.kt`, `maestro-orchestra/.../debug/TestOutputWriter.kt` + `FlowDebugOutput.kt`, `maestro-ios-driver/.../util/LocalSimulatorUtils.kt`, `maestro-client/.../drivers/AndroidDriver.kt` (screenrecord), `maestro-cli/.../report/JUnitTestSuiteReporter.kt` + `ReportFormat`, `maestro-cli/.../command/BugReportCommand.kt` (stub), `maestro-cli/.../util/ScreenshotUtils.kt` (takeDebugScreenshot), `maestro-ios/.../xctest/XCTestIOSDevice.kt` (DiagnosticReports reference).
