# Cloud Debug Artifacts — Design

**Date:** 2026-04-17
**Status:** Draft
**Scope:** Make the CLI-style debug output (commands metadata JSON + failure screenshot) available in cloud runs, uploaded as flat files into `RUN_ARTIFACTS`. Extract the shared write-path into `maestro-orchestra` so CLI and cloud share one implementation.

## Background

`TestDebugReporter` lives in `maestro-cli/src/main/java/maestro/cli/report/TestDebugReporter.kt`. It produces a debug bundle on local CLI runs:

- `commands-(flowName).json` — per-command metadata (sequence, timestamp, duration, status, error, hierarchy-on-failure)
- `screenshot-{status}-{timestamp}-(flowName).png` — per-command screenshots tagged with ✅ / ❌ / ⚠️
- `ai-(flowName).json` + `HtmlAITestSuiteReporter` output — AI suggestions (CLI-only feature)
- `maestro.log` — global log4j config side effect of `install()`

Cloud runs execute through `MaestroTestRunner.kt` in the copilot repo's `maestro-worker`. The worker consumes Maestro as a **git submodule** (not a Maven artifact). Today the worker uploads:
- Screen recording (single blob)
- Device logs tar (`$jobId/logs.zip`)
- Screenshots + MP4s from `screenshotsDir` (populated only by explicit `takeScreenshot` commands)
- Crash/ANR reports (`$jobId/app-crash-report.txt`, `$jobId/app-anr-report.txt`)

What's missing from cloud runs: the per-command metadata JSON and a fresh failure screenshot.

## Goal

1. Cloud runs produce the same `commands-(flowName).json` that CLI users get, uploaded to `RUN_ARTIFACTS` as a flat file under `$jobId/`.
2. Cloud runs capture a fresh failure screenshot (via `device.takeScreenshot()` at the moment of failure) and upload it as a flat file alongside the JSON.
3. CLI behavior and output byte-identical to today.

**Non-goals:**
- AI output (`ai-*.json`, HTML AI report) stays CLI-only.
- Per-command happy-path screenshots in the cloud bundle — worker only captures on failure.
- `maestro.log` in the cloud bundle — worker has its own logging pipeline.
- Concurrent flow execution inside a single worker process — worker runs one flow per process, so the existing `object TestDebugReporter` global-state pattern in CLI is fine.
- New `JobPaths` fields — reuse existing `jobPaths.artifactsFolder` ("$jobId/").

## Architecture

Host the shared types and the pure write-path function in `maestro-orchestra` (the module is already an `api` dependency of `maestro-client` and `maestro-ai`, and is pulled transitively by `maestro-worker` via the submodule). No new Gradle module. CLI keeps its facade.

```
┌────────────────────────────────┐    ┌──────────────────────────────────┐
│       maestro-orchestra        │    │          maestro-cli             │
│  (shared — CLI + worker use)   │    │  (CLI lifecycle, unchanged API)  │
├────────────────────────────────┤    ├──────────────────────────────────┤
│ package maestro.orchestra.debug│    │ object TestDebugReporter {       │
│                                │    │   fun install(...)               │
│ data class FlowDebugOutput     │    │   fun saveFlow(..., shardIndex)  │
│   (+ nested Screenshot)        │◄───┤     → delegates to               │
│ data class CommandDebugMeta    │    │       TestOutputWriter.saveFlow  │
│ enum class CommandStatus       │    │   fun saveSuggestions(...)       │
│                                │    │   fun deleteOldFiles(...)        │
│ object TestOutputWriter {      │    │   fun getDebugOutputPath()       │
│   fun saveFlow(                │    │ }                                │
│     path, flowName,            │    │ data class FlowAIOutput          │
│     debugOutput,               │    │ class HtmlAITestSuiteReporter    │
│     filenamePrefix = "",       │    └──────────────────────────────────┘
│     logPrefix = "",            │
│   )                            │    ┌──────────────────────────────────┐
│ }                              │◄───┤    maestro-worker (copilot)      │
└────────────────────────────────┘    ├──────────────────────────────────┤
                                      │ WorkerOrchestraState             │
                                      │   populates FlowDebugOutput      │
                                      │   per command; fresh screenshot  │
                                      │   on failure only.               │
                                      │ MaestroTestRunner                │
                                      │   storeDebugOutput() →           │
                                      │   TestOutputWriter.saveFlow() →  │
                                      │   fileStorage.create(            │
                                      │     RUN_ARTIFACTS, ...,          │
                                      │     "${jobPaths.artifactsFolder}"│
                                      │     + file.name                  │
                                      │   )                              │
                                      └──────────────────────────────────┘
```

## Detailed changes

### Part 1 — Maestro repo (`feat/test-output-shared` branch)

#### 1.0 Backfill `saveFlow` tests BEFORE moving the code

`TestDebugReporterTest` has nine tests today, all focused on `install`/`getDebugOutputPath`/`deleteOldFiles`/`saveSuggestions(empty)`. `saveFlow` — the function we're about to extract — has **zero direct test coverage**. Moving it without a safety net would silently hide regressions (emoji encoding, shard-prefix math, flow-name slash replacement, exception handling).

First commit on the branch adds these test cases to `TestDebugReporterTest` against the current CLI implementation:

- `saveFlow writes commands-(flowName).json when commands map is non-empty`
- `saveFlow writes no commands JSON when commands map is empty`
- `saveFlow copies screenshots with ✅ tag for COMPLETED status`
- `saveFlow copies screenshots with ❌ tag for FAILED status`
- `saveFlow copies screenshots with ⚠️ tag for WARNED status`
- `saveFlow copies screenshots with ﹖ tag for any other status`
- `saveFlow writes no screenshot files when screenshots list is empty`
- `saveFlow with shardIndex = 0 prefixes filenames with 'shard-1-'` (covers the n+1 translation)
- `saveFlow with shardIndex = null does not prefix filenames`
- `saveFlow replaces slashes in flowName with underscores in filenames`
- `saveFlow continues writing screenshots when JsonMappingException is thrown while serializing commands`

These tests must pass green on `main` before any code moves. Once the code moves to `maestro-orchestra` (step 1.2), the tests migrate alongside: they retarget `TestOutputWriter.saveFlow` directly (orchestra-side, using `filenamePrefix`/`logPrefix` params), and one small CLI-side test remains to prove the shard-index → prefix translation in the facade still produces `shard-1-…` names.

#### 1.1 Relocate types into `maestro-orchestra`

New package `maestro.orchestra.debug`:

- `FlowDebugOutput` (with nested `Screenshot`) — moved from `maestro.cli.report.TestDebugReporter.kt`
- `CommandDebugMetadata` — moved from same file
- `CommandStatus` — moved from `maestro.cli.runner`

Keep the class structure and field set identical to today. Only the package changes.

#### 1.2 Add `TestOutputWriter` in `maestro-orchestra`

New file `maestro-orchestra/src/main/kotlin/maestro/orchestra/debug/TestOutputWriter.kt`:

```kotlin
package maestro.orchestra.debug

object TestOutputWriter {
    private val mapper = jacksonObjectMapper()
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)
        .setSerializationInclusion(JsonInclude.Include.NON_EMPTY)
        .writerWithDefaultPrettyPrinter()
    private val logger = LoggerFactory.getLogger(TestOutputWriter::class.java)

    fun saveFlow(
        path: Path,
        flowName: String,
        debugOutput: FlowDebugOutput,
        filenamePrefix: String = "",
        logPrefix: String = "",
    ) {
        // commands JSON
        try {
            val commandMetadata = debugOutput.commands
            if (commandMetadata.isNotEmpty()) {
                val commandsFilename = "commands-$filenamePrefix(${flowName.replace("/", "_")}).json"
                val file = File(path.absolutePathString(), commandsFilename)
                commandMetadata.map { CommandDebugWrapper(it.key, it.value) }.let {
                    mapper.writeValue(file, it)
                }
            }
        } catch (e: JsonMappingException) {
            logger.error("${logPrefix}Unable to parse commands", e)
        }

        // screenshots with status-tagged filenames
        debugOutput.screenshots.forEach {
            val status = when (it.status) {
                CommandStatus.COMPLETED -> "✅"
                CommandStatus.FAILED -> "❌"
                CommandStatus.WARNED -> "⚠️"
                else -> "﹖"
            }
            val filename = "screenshot-$filenamePrefix$status-${it.timestamp}-(${flowName}).png"
            val file = File(path.absolutePathString(), filename)
            it.screenshot.copyTo(file)
        }
    }

    private data class CommandDebugWrapper(
        val command: MaestroCommand,
        val metadata: CommandDebugMetadata,
    )
}
```

Notes:
- Body lifted verbatim from `TestDebugReporter.saveFlow`, with the `shardIndex → shardPrefix/shardLogPrefix` translation removed.
- `CommandDebugWrapper` is an implementation detail — kept `private` inside the writer.
- Uses SLF4J `LoggerFactory` (already a dep of `maestro-orchestra`) instead of CLI's log4j `LogManager`.

#### 1.3 Update CLI facade to delegate

`maestro-cli/src/main/java/maestro/cli/report/TestDebugReporter.kt`:

```kotlin
fun saveFlow(
    flowName: String,
    debugOutput: FlowDebugOutput,
    path: Path,
    shardIndex: Int? = null,
) {
    val filenamePrefix = shardIndex?.let { "shard-${it + 1}-" }.orEmpty()
    val logPrefix = shardIndex?.let { "[shard ${it + 1}] " }.orEmpty()
    TestOutputWriter.saveFlow(path, flowName, debugOutput, filenamePrefix, logPrefix)
}
```

`saveSuggestions`, `install`, `deleteOldFiles`, `getDebugOutputPath`, `updateTestOutputDir` unchanged.

#### 1.4 Import-site cleanup across CLI

Grep for:
- `import maestro.cli.report.FlowDebugOutput`
- `import maestro.cli.report.TestDebugReporter.FlowDebugOutput` (if nested before move — it's top-level today)
- `import maestro.cli.report.CommandDebugMetadata`
- `import maestro.cli.runner.CommandStatus`
- `maestro.cli.report.Screenshot`

Update each to the new `maestro.orchestra.debug.*` FQN. The data classes don't change shape, only package.

If any downstream plugin/consumer references these from the old FQN, add `typealias` re-exports at the original locations to preserve source compatibility. Default stance: move with no typealias; add only if grep shows external consumers.

#### 1.5 Verification

- Existing CLI test suite passes (`./gradlew :maestro-cli:test`).
- Manual: run `maestro test --debug-output /tmp/debug <any-flow>` before and after on `main` — diff output directories. Only ordering of keys in JSON may differ; file names, content, structure identical.
- Diff should show zero meaningful changes to CLI output.

### Part 2 — Copilot repo (`feat/cloud-debug-artifacts` branch)

#### 2.1 Bump Maestro submodule pointer

Point the `Maestro/` submodule to the tip of `feat/test-output-shared` (or, after merge, to the merge commit on Maestro `main`).

#### 2.2 Extend `WorkerOrchestraState` to populate `FlowDebugOutput`

The worker's Orchestra callback sink (`WorkerOrchestraState` in `copilot/maestro-worker/.../maestro/`) gains a `FlowDebugOutput` field populated on each callback:

- **onCommandStart(cmd, index):** create `CommandDebugMetadata(sequenceNumber = index, timestamp = now)` and put in `flowDebugOutput.commands[cmd]`.
- **onCommandComplete(cmd):** set `status = COMPLETED`, `calculateDuration()`. No screenshot.
- **onCommandFailed(cmd, err, hierarchy):**
  - Mutate metadata: `status = FAILED`, `error = err`, `hierarchy = hierarchy`, `calculateDuration()`.
  - Take a **fresh** screenshot: `val bytes = device.takeScreenshot(); val f = Files.createTempFile("maestro-fail-", ".png").toFile(); f.writeBytes(bytes)`.
  - Append `FlowDebugOutput.Screenshot(file = f, timestamp = now, status = CommandStatus.FAILED)` to `flowDebugOutput.screenshots`.
- **onCommandWarned(cmd):** `status = WARNED`, `calculateDuration()`. No screenshot in cloud.

Exact hook names depend on what `OrchestraCallbackFactory` already exposes — the spec author will map these to existing callback APIs during plan writing. If the worker doesn't already have a `device` handle in the state object, pass it in at construction.

The existing API-streaming behavior in `WorkerOrchestraState` is untouched — this is additive.

#### 2.3 Add `storeDebugOutput()` in `MaestroTestRunner`

```kotlin
private fun storeDebugOutput() {
    val debugDir = Files.createTempDirectory("maestro-debug-")
    try {
        TestOutputWriter.saveFlow(
            path = debugDir,
            flowName = jobId,                          // or resolved flow name if available
            debugOutput = orchestraState.flowDebugOutput,
        )
        debugDir.toFile().listFiles()?.forEach { file ->
            fileStorage.create(
                FileStorageType.RUN_ARTIFACTS,
                file,
                "${jobPaths.artifactsFolder}${file.name}",
            )
        }
    } catch (e: Exception) {
        logger.warn("Failed to store debug output for $jobId", e)
    } finally {
        debugDir.toFile().deleteRecursively()
    }
}
```

Wire it into `collectAllArtifacts()`:

```kotlin
private suspend fun collectAllArtifacts() {
    storeScreenRecording()
    storeArtifacts()
    storeScreenshots()
    storeDebugOutput()   // NEW
}
```

The try/catch is intentional: debug output failures must not mask test failures or break the rest of artifact collection — same posture as `TestDebugReporter.deleteOldFiles` today.

#### 2.4 Flow name resolution

If the worker doesn't currently know the flow name at runtime (job payload may have a script name, file path, or nothing), use `jobId` as the flow name for the cloud bundle. Filename becomes `commands-({jobId}).json` — unambiguous within a single job.

If a flow name is retrievable from the `MaestroScript` or `JobPayload`, prefer that. Plan writer to verify during implementation.

#### 2.5 Verification

- Cloud unit/integration tests for `MaestroTestRunner` updated to assert `storeDebugOutput` is called in `collectAllArtifacts`.
- Manual: submit a passing flow → `RUN_ARTIFACTS` shows `$jobId/commands-({jobId}).json` only.
- Manual: submit a failing flow → `RUN_ARTIFACTS` shows `$jobId/commands-({jobId}).json` + `$jobId/screenshot-❌-{ts}-({jobId}).png`.
- Manual: force an `onCommandFailed` callback mid-flow (e.g., bad element selector) → verify the screenshot file is valid PNG and captures the post-failure device state.

## Data flow

```
Orchestra callback (per command)
  → WorkerOrchestraState callback hook
  → mutate flowDebugOutput.commands[cmd] (metadata)
  → on failure only: device.takeScreenshot() → tempFile → flowDebugOutput.screenshots += Screenshot(...)

(End of flow, in finally block)
  → MaestroTestRunner.collectAllArtifacts()
  → storeDebugOutput()
    → TestOutputWriter.saveFlow(tempDir, jobId, flowDebugOutput)
       → writes commands-({jobId}).json
       → copies screenshot files into tempDir with status-tagged names
    → fileStorage.create(RUN_ARTIFACTS, file, "$jobId/$filename") per file in tempDir
    → tempDir deleted
```

## Testing strategy

**Maestro repo (orchestra):**
- Migrated tests from step 1.0 run against `TestOutputWriter.saveFlow` directly. Coverage matrix preserved:
  - commands JSON written / not-written
  - all four status emoji tags (✅ / ❌ / ⚠️ / ﹖)
  - empty screenshots list
  - explicit `filenamePrefix` parameter produces `shard-1-...` names
  - default `filenamePrefix = ""` produces unprefixed names
  - slash replacement in flow name
  - `JsonMappingException` swallowed, screenshots still written

**Maestro repo (CLI):**
- `TestDebugReporterTest.saveFlow with shardIndex translation` — single test that `shardIndex = 2` at the facade produces `commands-shard-3-(flow).json` and `screenshot-shard-3-✅-...` output. Proves the CLI-side shard-index → prefix translation is unchanged.
- All remaining lifecycle tests (`install`, `getDebugOutputPath`, `deleteOldFiles`, `saveSuggestions` empty case) pass unchanged.

**Copilot repo (worker):**
- Unit test `WorkerOrchestraState` hooks populate `FlowDebugOutput` correctly (commands metadata per callback, screenshot only on failure).
- Unit test `MaestroTestRunner.storeDebugOutput` with a fake `FileStorage` — assert files uploaded under `${jobPaths.artifactsFolder}`.
- Unit test debug output failure doesn't mask test failures — inject a `TestOutputWriter` exception, assert `collectAllArtifacts` still completes and test outcome propagates.
- Integration test end-to-end for a failing flow in the worker's existing harness.

## Risks / Open items

1. **Flow name availability.** If the worker has no natural flow name, `jobId` is a fallback. Minor UX issue — users see `commands-(cloud-run-abc123).json` instead of the human flow name. Not blocking.
2. **Screenshot capture failures.** `device.takeScreenshot()` may itself throw (e.g., device disconnected). Wrap the failure-hook screenshot call in its own try/catch — log warning, skip the screenshot, keep the metadata.
3. **`CommandStatus` enum relocation.** Grep in Maestro shows usages in `maestro-cli` only. Low risk, but external plugins on the old FQN would break. If any show up, add `typealias` re-export in `maestro.cli.runner`.
4. **Submodule pointer sync.** Copilot branch can't land until Maestro branch is merged (or copilot pins to the Maestro branch SHA temporarily). Standard submodule workflow — no tooling changes needed.
5. **AI output types in orchestra.** `FlowAIOutput` / `SingleScreenFlowAIOutput` stay in `maestro-cli` — we do not move them. Avoids leaking `maestro.ai.cloud.Defect` into the orchestra write-path and keeps `saveSuggestions` CLI-only.
6. **Logger name change in CLI-visible error log.** Today `TestDebugReporter.saveFlow` logs parse errors via log4j `LogManager.getLogger(TestDebugReporter::class.java)` → logger name `maestro.cli.report.TestDebugReporter`. After move, the `${logPrefix}Unable to parse commands` error is emitted by the orchestra writer under `maestro.orchestra.debug.TestOutputWriter`. Behavior (that the line is logged) is identical; only the category changes. If any CLI log4j filter rule pins to the old category, it won't catch the new one. Grep CLI's log4j config during implementation and update if needed.

## Rollout

1. Land Maestro branch. CI green, manual CLI diff shows no regression. Merge to Maestro `main`.
2. Copilot branch bumps submodule pointer to the Maestro merge commit, adds worker-side changes, CI green. Merge to copilot `main`.
3. Deploy worker. Observe cloud runs in staging produce the new flat files in `RUN_ARTIFACTS`.
4. Update cloud UI (separate follow-up work, out of scope here) to surface/download these new artifacts.
