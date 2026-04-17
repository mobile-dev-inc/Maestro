# Cloud Debug Artifacts — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make CLI's `TestDebugReporter` debug bundle (commands metadata JSON + failure screenshot) available in cloud runs, uploaded as flat files under `jobPaths.artifactsFolder`. Extract shared write-path into `maestro-orchestra` so CLI and cloud share one implementation; CLI behavior stays byte-identical.

**Architecture:** Move `FlowDebugOutput`, `CommandDebugMetadata`, `CommandStatus`, and a new pure-I/O `TestOutputWriter.saveFlow` from `maestro-cli` to `maestro-orchestra`. CLI's `TestDebugReporter` keeps its public API but delegates `saveFlow` to the shared writer (translating its `shardIndex` param into filename/log prefix strings). Worker's `WorkerOrchestraState` populates a `FlowDebugOutput` from existing Orchestra callbacks, taking a fresh `device.takeScreenshot()` only on failure. `MaestroTestRunner.collectAllArtifacts` adds a `storeDebugOutput()` step that writes via `TestOutputWriter` to a temp dir and uploads each resulting file flat into `$jobId/`.

**Tech Stack:** Kotlin/JVM 17, Gradle (Maestro), Gradle (copilot maestro-worker), JUnit 5, Google Truth, MockK, jackson-databind. Maestro is consumed by copilot as a **git submodule** — not Maven Central.

**Repos:** Two branches, two PRs.
- Maestro repo (`/Users/amanjeetsingh/Desktop/Workspace/Maestro/`) — branch `feat/test-output-shared`
- Copilot repo (`/Users/amanjeetsingh/Desktop/Workspace/copilot/`) — branch `feat/cloud-debug-artifacts` (bumps the Maestro submodule pointer)

---

## Part A — Maestro repo (`feat/test-output-shared` branch)

### Task 1: Create branch and backfill `saveFlow` tests against current CLI code

**Files:**
- Modify: `/Users/amanjeetsingh/Desktop/Workspace/Maestro/maestro-cli/src/test/kotlin/maestro/cli/report/TestDebugReporterTest.kt`

The current implementation of `TestDebugReporter.saveFlow` has no direct test. Before moving it, add tests that pin its behavior. These tests must pass on `main` today.

- [ ] **Step 1: Create branch**

```bash
cd /Users/amanjeetsingh/Desktop/Workspace/Maestro
git checkout -b feat/test-output-shared
```

- [ ] **Step 2: Add `saveFlow` test cases to `TestDebugReporterTest`**

Append these tests to `maestro-cli/src/test/kotlin/maestro/cli/report/TestDebugReporterTest.kt` (inside the `class TestDebugReporterTest { ... }` block, before the closing brace):

```kotlin
    @Test
    fun `saveFlow writes commands JSON when commands map is non-empty`() {
        val outputDir = Files.createDirectories(tempDir.resolve("out"))
        val cmd = maestro.orchestra.MaestroCommand(tapOnElement = null)
        val debug = FlowDebugOutput().apply {
            commands[cmd] = CommandDebugMetadata(
                status = maestro.cli.runner.CommandStatus.COMPLETED,
                timestamp = 123L,
                duration = 10L,
                sequenceNumber = 0,
            )
        }

        TestDebugReporter.saveFlow("my_flow", debug, outputDir)

        val file = outputDir.resolve("commands-(my_flow).json").toFile()
        assertThat(file.exists()).isTrue()
        assertThat(file.readText()).contains("\"status\" : \"COMPLETED\"")
    }

    @Test
    fun `saveFlow writes no commands JSON when commands map is empty`() {
        val outputDir = Files.createDirectories(tempDir.resolve("out"))
        val debug = FlowDebugOutput()

        TestDebugReporter.saveFlow("my_flow", debug, outputDir)

        val listed = outputDir.toFile().listFiles()?.toList().orEmpty()
        assertThat(listed.any { it.name.startsWith("commands-") }).isFalse()
    }

    @Test
    fun `saveFlow tags screenshots with COMPLETED emoji`() {
        val outputDir = Files.createDirectories(tempDir.resolve("out"))
        val shot = Files.createFile(tempDir.resolve("raw.png")).toFile()
        shot.writeBytes(byteArrayOf(1, 2, 3))
        val debug = FlowDebugOutput().apply {
            screenshots.add(FlowDebugOutput.Screenshot(shot, 999L, maestro.cli.runner.CommandStatus.COMPLETED))
        }

        TestDebugReporter.saveFlow("my_flow", debug, outputDir)

        val written = outputDir.toFile().listFiles()!!
            .first { it.name.startsWith("screenshot-") }
        assertThat(written.name).isEqualTo("screenshot-✅-999-(my_flow).png")
        assertThat(written.readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
    }

    @Test
    fun `saveFlow tags screenshots with FAILED emoji`() {
        val outputDir = Files.createDirectories(tempDir.resolve("out"))
        val shot = Files.createFile(tempDir.resolve("raw.png")).toFile()
        val debug = FlowDebugOutput().apply {
            screenshots.add(FlowDebugOutput.Screenshot(shot, 111L, maestro.cli.runner.CommandStatus.FAILED))
        }

        TestDebugReporter.saveFlow("my_flow", debug, outputDir)

        val written = outputDir.toFile().listFiles()!!
            .first { it.name.startsWith("screenshot-") }
        assertThat(written.name).isEqualTo("screenshot-❌-111-(my_flow).png")
    }

    @Test
    fun `saveFlow tags screenshots with WARNED emoji`() {
        val outputDir = Files.createDirectories(tempDir.resolve("out"))
        val shot = Files.createFile(tempDir.resolve("raw.png")).toFile()
        val debug = FlowDebugOutput().apply {
            screenshots.add(FlowDebugOutput.Screenshot(shot, 222L, maestro.cli.runner.CommandStatus.WARNED))
        }

        TestDebugReporter.saveFlow("my_flow", debug, outputDir)

        val written = outputDir.toFile().listFiles()!!
            .first { it.name.startsWith("screenshot-") }
        assertThat(written.name).isEqualTo("screenshot-⚠\uFE0F-222-(my_flow).png")
    }

    @Test
    fun `saveFlow tags screenshots with unknown emoji for other statuses`() {
        val outputDir = Files.createDirectories(tempDir.resolve("out"))
        val shot = Files.createFile(tempDir.resolve("raw.png")).toFile()
        val debug = FlowDebugOutput().apply {
            screenshots.add(FlowDebugOutput.Screenshot(shot, 333L, maestro.cli.runner.CommandStatus.SKIPPED))
        }

        TestDebugReporter.saveFlow("my_flow", debug, outputDir)

        val written = outputDir.toFile().listFiles()!!
            .first { it.name.startsWith("screenshot-") }
        assertThat(written.name).isEqualTo("screenshot-﹖-333-(my_flow).png")
    }

    @Test
    fun `saveFlow writes no screenshot files when screenshots list is empty`() {
        val outputDir = Files.createDirectories(tempDir.resolve("out"))
        val debug = FlowDebugOutput()

        TestDebugReporter.saveFlow("my_flow", debug, outputDir)

        val listed = outputDir.toFile().listFiles()?.toList().orEmpty()
        assertThat(listed.any { it.name.startsWith("screenshot-") }).isFalse()
    }

    @Test
    fun `saveFlow with shardIndex 0 prefixes filenames with shard-1-`() {
        val outputDir = Files.createDirectories(tempDir.resolve("out"))
        val shot = Files.createFile(tempDir.resolve("raw.png")).toFile()
        val cmd = maestro.orchestra.MaestroCommand(tapOnElement = null)
        val debug = FlowDebugOutput().apply {
            commands[cmd] = CommandDebugMetadata(
                status = maestro.cli.runner.CommandStatus.COMPLETED,
                timestamp = 1L,
            )
            screenshots.add(FlowDebugOutput.Screenshot(shot, 555L, maestro.cli.runner.CommandStatus.COMPLETED))
        }

        TestDebugReporter.saveFlow("my_flow", debug, outputDir, shardIndex = 0)

        val names = outputDir.toFile().listFiles()!!.map { it.name }
        assertThat(names).contains("commands-shard-1-(my_flow).json")
        assertThat(names).contains("screenshot-shard-1-✅-555-(my_flow).png")
    }

    @Test
    fun `saveFlow with shardIndex null does not prefix filenames`() {
        val outputDir = Files.createDirectories(tempDir.resolve("out"))
        val shot = Files.createFile(tempDir.resolve("raw.png")).toFile()
        val cmd = maestro.orchestra.MaestroCommand(tapOnElement = null)
        val debug = FlowDebugOutput().apply {
            commands[cmd] = CommandDebugMetadata(
                status = maestro.cli.runner.CommandStatus.COMPLETED,
                timestamp = 1L,
            )
            screenshots.add(FlowDebugOutput.Screenshot(shot, 555L, maestro.cli.runner.CommandStatus.COMPLETED))
        }

        TestDebugReporter.saveFlow("my_flow", debug, outputDir)

        val names = outputDir.toFile().listFiles()!!.map { it.name }
        assertThat(names).contains("commands-(my_flow).json")
        assertThat(names).contains("screenshot-✅-555-(my_flow).png")
    }

    @Test
    fun `saveFlow replaces slashes in flow name with underscores in commands filename`() {
        val outputDir = Files.createDirectories(tempDir.resolve("out"))
        val cmd = maestro.orchestra.MaestroCommand(tapOnElement = null)
        val debug = FlowDebugOutput().apply {
            commands[cmd] = CommandDebugMetadata(status = maestro.cli.runner.CommandStatus.COMPLETED)
        }

        TestDebugReporter.saveFlow("feature/login", debug, outputDir)

        assertThat(outputDir.resolve("commands-(feature_login).json").toFile().exists()).isTrue()
    }
```

- [ ] **Step 3: Run the new tests and verify they all pass against current CLI code**

```bash
cd /Users/amanjeetsingh/Desktop/Workspace/Maestro
./gradlew :maestro-cli:test --tests maestro.cli.report.TestDebugReporterTest
```

Expected: BUILD SUCCESSFUL, all tests (including the 10 new ones) pass.

If any of the new tests fail, that means the test captures behavior I expected but the code doesn't actually produce. Read the actual filename/content the test produced and adjust the test to match current behavior — we are pinning *actual* behavior, not fixing it.

- [ ] **Step 4: Commit**

```bash
cd /Users/amanjeetsingh/Desktop/Workspace/Maestro
git add maestro-cli/src/test/kotlin/maestro/cli/report/TestDebugReporterTest.kt
git commit -m "test(cli): pin TestDebugReporter.saveFlow behavior before refactor"
```

---

### Task 2: Move `CommandStatus` enum to `maestro-orchestra`

**Files:**
- Create: `/Users/amanjeetsingh/Desktop/Workspace/Maestro/maestro-orchestra/src/main/kotlin/maestro/orchestra/debug/CommandStatus.kt`
- Delete: `/Users/amanjeetsingh/Desktop/Workspace/Maestro/maestro-cli/src/main/java/maestro/cli/runner/CommandStatus.kt`
- Modify (8 files add import of `maestro.orchestra.debug.CommandStatus`; 2 may need to drop a same-package reference):
  - `maestro-cli/src/main/java/maestro/cli/report/TestDebugReporter.kt`
  - `maestro-cli/src/main/java/maestro/cli/util/ScreenshotUtils.kt`
  - `maestro-cli/src/main/java/maestro/cli/runner/resultview/AnsiResultView.kt`
  - `maestro-cli/src/main/java/maestro/cli/runner/resultview/PlainTextResultView.kt`
  - `maestro-cli/src/main/java/maestro/cli/runner/MaestroCommandRunner.kt`
  - `maestro-cli/src/main/java/maestro/cli/runner/TestSuiteInteractor.kt`
  - `maestro-cli/src/test/kotlin/maestro/cli/runner/resultview/PlainTextResultViewTest.kt`
  - `maestro-cli/src/test/kotlin/maestro/cli/report/TestDebugReporterTest.kt` (the tests added in Task 1 used `maestro.cli.runner.CommandStatus` FQN)

- [ ] **Step 1: Create the new file in `maestro-orchestra`**

Path: `maestro-orchestra/src/main/kotlin/maestro/orchestra/debug/CommandStatus.kt`

```kotlin
/*
 *
 *  Copyright (c) 2022 mobile.dev inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 */

package maestro.orchestra.debug

enum class CommandStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    WARNED,
    SKIPPED,
}
```

- [ ] **Step 2: Delete the old file**

```bash
rm /Users/amanjeetsingh/Desktop/Workspace/Maestro/maestro-cli/src/main/java/maestro/cli/runner/CommandStatus.kt
```

- [ ] **Step 3: Update imports in each of the 8 files**

In every file listed in **Files** above, replace `import maestro.cli.runner.CommandStatus` with `import maestro.orchestra.debug.CommandStatus`. For same-package references (`TestSuiteInteractor.kt` in `maestro.cli.runner` and `MaestroCommandRunner.kt` in `maestro.cli.runner`), add an explicit `import maestro.orchestra.debug.CommandStatus` line near the top of the file (after the existing imports).

In the test file `TestDebugReporterTest.kt`, replace every FQN `maestro.cli.runner.CommandStatus` that Task 1 embedded with just `CommandStatus`, and add `import maestro.orchestra.debug.CommandStatus` at the top of the test file.

Use this exact sed helper to do the source file updates, then grep to confirm none remain:

```bash
cd /Users/amanjeetsingh/Desktop/Workspace/Maestro
grep -rl "maestro\.cli\.runner\.CommandStatus" maestro-cli/src | xargs sed -i '' 's|maestro\.cli\.runner\.CommandStatus|maestro.orchestra.debug.CommandStatus|g'
grep -r "maestro\.cli\.runner\.CommandStatus" maestro-cli/src && echo "FAIL: remaining references" || echo "OK"
```

Then open `TestSuiteInteractor.kt` and `MaestroCommandRunner.kt` and confirm they now have `import maestro.orchestra.debug.CommandStatus` near the top (the sed run above would not have added one if they were using the old same-package reference). If either is missing the import, add it manually.

- [ ] **Step 4: Build to verify compilation**

```bash
cd /Users/amanjeetsingh/Desktop/Workspace/Maestro
./gradlew :maestro-orchestra:compileKotlin :maestro-cli:compileKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Run CLI test suite to verify no behavioral regression**

```bash
./gradlew :maestro-cli:test
```

Expected: BUILD SUCCESSFUL. The 10 new `saveFlow` tests still pass; all other tests still pass.

- [ ] **Step 6: Commit**

```bash
cd /Users/amanjeetsingh/Desktop/Workspace/Maestro
git add -A
git commit -m "refactor: move CommandStatus from maestro-cli to maestro-orchestra

Relocates the enum to maestro.orchestra.debug so cloud (maestro-worker)
can reference it once the shared TestOutputWriter lands. CLI call sites
updated to import from the new FQN; no behavior change."
```

---

### Task 3: Move `FlowDebugOutput` and `CommandDebugMetadata` to `maestro-orchestra`

**Files:**
- Create: `/Users/amanjeetsingh/Desktop/Workspace/Maestro/maestro-orchestra/src/main/kotlin/maestro/orchestra/debug/FlowDebugOutput.kt`
- Modify: `/Users/amanjeetsingh/Desktop/Workspace/Maestro/maestro-cli/src/main/java/maestro/cli/report/TestDebugReporter.kt` (remove the two data-class definitions near the bottom)
- Modify: 4 CLI files plus the CLI test file to retarget imports
  - `maestro-cli/src/main/java/maestro/cli/runner/TestSuiteInteractor.kt`
  - `maestro-cli/src/main/java/maestro/cli/runner/TestRunner.kt`
  - `maestro-cli/src/main/java/maestro/cli/runner/MaestroCommandRunner.kt`
  - `maestro-cli/src/main/java/maestro/cli/util/ScreenshotUtils.kt`
  - `maestro-cli/src/test/kotlin/maestro/cli/report/TestDebugReporterTest.kt`

- [ ] **Step 1: Create the new file in `maestro-orchestra`**

Path: `maestro-orchestra/src/main/kotlin/maestro/orchestra/debug/FlowDebugOutput.kt`

```kotlin
package maestro.orchestra.debug

import maestro.MaestroException
import maestro.TreeNode
import maestro.orchestra.MaestroCommand
import java.io.File
import java.util.IdentityHashMap

data class CommandDebugMetadata(
    var status: CommandStatus? = null,
    var timestamp: Long? = null,
    var duration: Long? = null,
    var error: Throwable? = null,
    var hierarchy: TreeNode? = null,
    var sequenceNumber: Int = 0,
    var evaluatedCommand: MaestroCommand? = null,
) {
    fun calculateDuration() {
        if (timestamp != null) {
            duration = System.currentTimeMillis() - timestamp!!
        }
    }
}

data class FlowDebugOutput(
    val commands: IdentityHashMap<MaestroCommand, CommandDebugMetadata> = IdentityHashMap<MaestroCommand, CommandDebugMetadata>(),
    val screenshots: MutableList<Screenshot> = mutableListOf(),
    var exception: MaestroException? = null,
) {
    data class Screenshot(
        val screenshot: File,
        val timestamp: Long,
        val status: CommandStatus,
    )
}
```

- [ ] **Step 2: Remove the moved data classes from `TestDebugReporter.kt`**

In `maestro-cli/src/main/java/maestro/cli/report/TestDebugReporter.kt`, delete these blocks (lines ~218–244 in the current file):

```kotlin
data class CommandDebugMetadata(
    var status: CommandStatus? = null,
    ...
)

data class FlowDebugOutput(
    val commands: IdentityHashMap<MaestroCommand, CommandDebugMetadata> = ...,
    val screenshots: MutableList<Screenshot> = mutableListOf(),
    var exception: MaestroException? = null,
) {
    data class Screenshot(
        val screenshot: File,
        val timestamp: Long,
        val status: CommandStatus,
    )
}
```

Keep `FlowAIOutput` and `SingleScreenFlowAIOutput` in the CLI file (they are NOT moving — see Task 4 note).

Add to the top of `TestDebugReporter.kt`:

```kotlin
import maestro.orchestra.debug.CommandDebugMetadata
import maestro.orchestra.debug.FlowDebugOutput
```

The existing `import maestro.cli.runner.CommandStatus` was already replaced in Task 2.

- [ ] **Step 3: Update imports in the 4 consuming CLI files and the test file**

```bash
cd /Users/amanjeetsingh/Desktop/Workspace/Maestro
grep -rl "maestro\.cli\.report\.\(FlowDebugOutput\|CommandDebugMetadata\)" maestro-cli/src | \
    xargs sed -i '' -E \
      -e 's|maestro\.cli\.report\.FlowDebugOutput|maestro.orchestra.debug.FlowDebugOutput|g' \
      -e 's|maestro\.cli\.report\.CommandDebugMetadata|maestro.orchestra.debug.CommandDebugMetadata|g'

grep -r "maestro\.cli\.report\.\(FlowDebugOutput\|CommandDebugMetadata\)" maestro-cli/src && echo "FAIL: remaining references" || echo "OK"
```

- [ ] **Step 4: Build and run the CLI test suite**

```bash
cd /Users/amanjeetsingh/Desktop/Workspace/Maestro
./gradlew :maestro-orchestra:compileKotlin :maestro-cli:compileKotlin :maestro-cli:test
```

Expected: BUILD SUCCESSFUL. All `saveFlow` tests from Task 1 still pass.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: move FlowDebugOutput and CommandDebugMetadata to maestro-orchestra

Data types for debug output now live in maestro.orchestra.debug so the
cloud worker can consume them. TestDebugReporter.kt in CLI retains only
the CLI-specific lifecycle (install, saveSuggestions, AI output types,
etc.). No behavior change; all CLI call sites updated."
```

---

### Task 4: Create `TestOutputWriter` in `maestro-orchestra`

**Files:**
- Create: `/Users/amanjeetsingh/Desktop/Workspace/Maestro/maestro-orchestra/src/main/kotlin/maestro/orchestra/debug/TestOutputWriter.kt`

- [ ] **Step 1: Create the writer file**

Path: `maestro-orchestra/src/main/kotlin/maestro/orchestra/debug/TestOutputWriter.kt`

```kotlin
package maestro.orchestra.debug

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import maestro.orchestra.MaestroCommand
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path
import kotlin.io.path.absolutePathString

/**
 * Pure write-path for debug artifacts produced during a flow run.
 *
 * The CLI's [maestro.cli.report.TestDebugReporter] is the long-standing
 * caller; the cloud worker ([dev.mobile.maestro.worker.MaestroTestRunner])
 * is the second caller. Both pass an already-populated [FlowDebugOutput]
 * and a target directory.
 */
object TestOutputWriter {

    private val logger = LoggerFactory.getLogger(TestOutputWriter::class.java)
    private val mapper = jacksonObjectMapper()
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)
        .setSerializationInclusion(JsonInclude.Include.NON_EMPTY)
        .writerWithDefaultPrettyPrinter()

    /**
     * Writes debug artifacts for one flow into [path].
     *
     * @param path destination directory (must exist).
     * @param flowName human-readable flow name; slashes are replaced with underscores in filenames.
     * @param debugOutput accumulated debug state for the flow.
     * @param filenamePrefix inserted into filenames right after the `commands-`/`screenshot-` stem.
     *                      CLI passes `"shard-1-"` for shard index 0, `""` otherwise.
     * @param logPrefix prepended to error log messages from this writer. CLI passes `"[shard 1] "` etc.
     */
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
                CommandStatus.WARNED -> "⚠\uFE0F"
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

Note: the warning emoji `⚠\uFE0F` (warning sign + variation selector) preserves byte-for-byte CLI output — the source file today has `"⚠️"` which is the same two-codepoint sequence.

- [ ] **Step 2: Compile**

```bash
cd /Users/amanjeetsingh/Desktop/Workspace/Maestro
./gradlew :maestro-orchestra:compileKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add maestro-orchestra/src/main/kotlin/maestro/orchestra/debug/TestOutputWriter.kt
git commit -m "feat(orchestra): add TestOutputWriter for shared debug artifact I/O

Hosts the pure commands-JSON + screenshot copy logic previously embedded
in maestro-cli's TestDebugReporter.saveFlow. Accepts filenamePrefix and
logPrefix so CLI can plug in shard-index naming and the cloud worker can
call with defaults."
```

---

### Task 5: Make CLI `TestDebugReporter.saveFlow` delegate to `TestOutputWriter`

**Files:**
- Modify: `/Users/amanjeetsingh/Desktop/Workspace/Maestro/maestro-cli/src/main/java/maestro/cli/report/TestDebugReporter.kt`

- [ ] **Step 1: Replace the body of `saveFlow` with delegation**

In `maestro-cli/src/main/java/maestro/cli/report/TestDebugReporter.kt`, find the existing `fun saveFlow(...)` (the one that takes `flowName, debugOutput, path, shardIndex`) and replace its body. The final function should be:

```kotlin
    /**
     * Save debug information about a single flow, after it has finished.
     * Delegates to [maestro.orchestra.debug.TestOutputWriter] so CLI and cloud
     * share the same on-disk output format.
     */
    fun saveFlow(flowName: String, debugOutput: FlowDebugOutput, path: Path, shardIndex: Int? = null) {
        val filenamePrefix = shardIndex?.let { "shard-${it + 1}-" }.orEmpty()
        val logPrefix = shardIndex?.let { "[shard ${it + 1}] " }.orEmpty()
        TestOutputWriter.saveFlow(path, flowName, debugOutput, filenamePrefix, logPrefix)
    }
```

Add at the top of the file:

```kotlin
import maestro.orchestra.debug.TestOutputWriter
```

Remove now-unused imports from `TestDebugReporter.kt`:

```kotlin
import com.fasterxml.jackson.databind.JsonMappingException     // no longer referenced
```

(Leave all other imports — `install` / `saveSuggestions` still use them.)

- [ ] **Step 2: Build**

```bash
cd /Users/amanjeetsingh/Desktop/Workspace/Maestro
./gradlew :maestro-cli:compileKotlin
```

Expected: BUILD SUCCESSFUL. If the build complains about unused imports (Kotlin does not fail on them, but style may), resolve via IDE or ignore.

- [ ] **Step 3: Run Task 1's `saveFlow` tests — this is the backward-compat proof**

```bash
./gradlew :maestro-cli:test --tests maestro.cli.report.TestDebugReporterTest
```

Expected: BUILD SUCCESSFUL. Every test added in Task 1 still passes — proving the CLI facade produces byte-identical output after delegation.

If any test fails, the delegation broke behavior. Do not proceed. Inspect the diff, typically one of:
- `filenamePrefix` / `logPrefix` argument order mismatched
- warning emoji codepoint sequence changed (re-check `⚠\uFE0F`)
- CLI imported a different `FlowDebugOutput` (e.g., still the old package)

- [ ] **Step 4: Run the full CLI test suite**

```bash
./gradlew :maestro-cli:test
```

Expected: BUILD SUCCESSFUL. All tests pass.

- [ ] **Step 5: Commit**

```bash
git add maestro-cli/src/main/java/maestro/cli/report/TestDebugReporter.kt
git commit -m "refactor(cli): delegate TestDebugReporter.saveFlow to TestOutputWriter

Public API of TestDebugReporter.saveFlow is unchanged (still accepts
shardIndex: Int?); the body is now a thin translation that maps the
shard index into filenamePrefix / logPrefix strings and delegates to
maestro.orchestra.debug.TestOutputWriter. saveSuggestions, install,
deleteOldFiles, getDebugOutputPath remain CLI-only. Output is byte-identical
(verified by backfilled tests from the previous commit)."
```

---

### Task 6: Migrate `saveFlow` tests to `maestro-orchestra`; trim CLI test file

**Files:**
- Create: `/Users/amanjeetsingh/Desktop/Workspace/Maestro/maestro-orchestra/src/test/kotlin/maestro/orchestra/debug/TestOutputWriterTest.kt`
- Modify: `/Users/amanjeetsingh/Desktop/Workspace/Maestro/maestro-cli/src/test/kotlin/maestro/cli/report/TestDebugReporterTest.kt` (remove the 10 backfilled tests, add one shard-index integration test)

- [ ] **Step 1: Create the orchestra test file**

Path: `maestro-orchestra/src/test/kotlin/maestro/orchestra/debug/TestOutputWriterTest.kt`

```kotlin
package maestro.orchestra.debug

import com.google.common.truth.Truth.assertThat
import maestro.orchestra.MaestroCommand
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class TestOutputWriterTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `saveFlow writes commands JSON when commands map is non-empty`() {
        val outputDir = Files.createDirectories(tempDir.resolve("out"))
        val cmd = MaestroCommand(tapOnElement = null)
        val debug = FlowDebugOutput().apply {
            commands[cmd] = CommandDebugMetadata(
                status = CommandStatus.COMPLETED,
                timestamp = 123L,
                duration = 10L,
                sequenceNumber = 0,
            )
        }

        TestOutputWriter.saveFlow(outputDir, "my_flow", debug)

        val file = outputDir.resolve("commands-(my_flow).json").toFile()
        assertThat(file.exists()).isTrue()
        assertThat(file.readText()).contains("\"status\" : \"COMPLETED\"")
    }

    @Test
    fun `saveFlow writes no commands JSON when commands map is empty`() {
        val outputDir = Files.createDirectories(tempDir.resolve("out"))
        val debug = FlowDebugOutput()

        TestOutputWriter.saveFlow(outputDir, "my_flow", debug)

        val listed = outputDir.toFile().listFiles()?.toList().orEmpty()
        assertThat(listed.any { it.name.startsWith("commands-") }).isFalse()
    }

    @Test
    fun `saveFlow tags screenshots with COMPLETED emoji`() {
        val outputDir = Files.createDirectories(tempDir.resolve("out"))
        val shot = Files.createFile(tempDir.resolve("raw.png")).toFile()
        shot.writeBytes(byteArrayOf(1, 2, 3))
        val debug = FlowDebugOutput().apply {
            screenshots.add(FlowDebugOutput.Screenshot(shot, 999L, CommandStatus.COMPLETED))
        }

        TestOutputWriter.saveFlow(outputDir, "my_flow", debug)

        val written = outputDir.toFile().listFiles()!!
            .first { it.name.startsWith("screenshot-") }
        assertThat(written.name).isEqualTo("screenshot-✅-999-(my_flow).png")
        assertThat(written.readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
    }

    @Test
    fun `saveFlow tags screenshots with FAILED emoji`() {
        val outputDir = Files.createDirectories(tempDir.resolve("out"))
        val shot = Files.createFile(tempDir.resolve("raw.png")).toFile()
        val debug = FlowDebugOutput().apply {
            screenshots.add(FlowDebugOutput.Screenshot(shot, 111L, CommandStatus.FAILED))
        }

        TestOutputWriter.saveFlow(outputDir, "my_flow", debug)

        val written = outputDir.toFile().listFiles()!!
            .first { it.name.startsWith("screenshot-") }
        assertThat(written.name).isEqualTo("screenshot-❌-111-(my_flow).png")
    }

    @Test
    fun `saveFlow tags screenshots with WARNED emoji`() {
        val outputDir = Files.createDirectories(tempDir.resolve("out"))
        val shot = Files.createFile(tempDir.resolve("raw.png")).toFile()
        val debug = FlowDebugOutput().apply {
            screenshots.add(FlowDebugOutput.Screenshot(shot, 222L, CommandStatus.WARNED))
        }

        TestOutputWriter.saveFlow(outputDir, "my_flow", debug)

        val written = outputDir.toFile().listFiles()!!
            .first { it.name.startsWith("screenshot-") }
        assertThat(written.name).isEqualTo("screenshot-⚠\uFE0F-222-(my_flow).png")
    }

    @Test
    fun `saveFlow tags screenshots with unknown emoji for other statuses`() {
        val outputDir = Files.createDirectories(tempDir.resolve("out"))
        val shot = Files.createFile(tempDir.resolve("raw.png")).toFile()
        val debug = FlowDebugOutput().apply {
            screenshots.add(FlowDebugOutput.Screenshot(shot, 333L, CommandStatus.SKIPPED))
        }

        TestOutputWriter.saveFlow(outputDir, "my_flow", debug)

        val written = outputDir.toFile().listFiles()!!
            .first { it.name.startsWith("screenshot-") }
        assertThat(written.name).isEqualTo("screenshot-﹖-333-(my_flow).png")
    }

    @Test
    fun `saveFlow writes no screenshot files when screenshots list is empty`() {
        val outputDir = Files.createDirectories(tempDir.resolve("out"))
        val debug = FlowDebugOutput()

        TestOutputWriter.saveFlow(outputDir, "my_flow", debug)

        val listed = outputDir.toFile().listFiles()?.toList().orEmpty()
        assertThat(listed.any { it.name.startsWith("screenshot-") }).isFalse()
    }

    @Test
    fun `saveFlow with filenamePrefix prefixes command and screenshot filenames`() {
        val outputDir = Files.createDirectories(tempDir.resolve("out"))
        val shot = Files.createFile(tempDir.resolve("raw.png")).toFile()
        val cmd = MaestroCommand(tapOnElement = null)
        val debug = FlowDebugOutput().apply {
            commands[cmd] = CommandDebugMetadata(status = CommandStatus.COMPLETED, timestamp = 1L)
            screenshots.add(FlowDebugOutput.Screenshot(shot, 555L, CommandStatus.COMPLETED))
        }

        TestOutputWriter.saveFlow(outputDir, "my_flow", debug, filenamePrefix = "shard-1-")

        val names = outputDir.toFile().listFiles()!!.map { it.name }
        assertThat(names).contains("commands-shard-1-(my_flow).json")
        assertThat(names).contains("screenshot-shard-1-✅-555-(my_flow).png")
    }

    @Test
    fun `saveFlow with default filenamePrefix does not prefix filenames`() {
        val outputDir = Files.createDirectories(tempDir.resolve("out"))
        val shot = Files.createFile(tempDir.resolve("raw.png")).toFile()
        val cmd = MaestroCommand(tapOnElement = null)
        val debug = FlowDebugOutput().apply {
            commands[cmd] = CommandDebugMetadata(status = CommandStatus.COMPLETED, timestamp = 1L)
            screenshots.add(FlowDebugOutput.Screenshot(shot, 555L, CommandStatus.COMPLETED))
        }

        TestOutputWriter.saveFlow(outputDir, "my_flow", debug)

        val names = outputDir.toFile().listFiles()!!.map { it.name }
        assertThat(names).contains("commands-(my_flow).json")
        assertThat(names).contains("screenshot-✅-555-(my_flow).png")
    }

    @Test
    fun `saveFlow replaces slashes in flow name with underscores in commands filename`() {
        val outputDir = Files.createDirectories(tempDir.resolve("out"))
        val cmd = MaestroCommand(tapOnElement = null)
        val debug = FlowDebugOutput().apply {
            commands[cmd] = CommandDebugMetadata(status = CommandStatus.COMPLETED)
        }

        TestOutputWriter.saveFlow(outputDir, "feature/login", debug)

        assertThat(outputDir.resolve("commands-(feature_login).json").toFile().exists()).isTrue()
    }
}
```

- [ ] **Step 2: Remove the 10 backfilled tests from the CLI test file**

In `maestro-cli/src/test/kotlin/maestro/cli/report/TestDebugReporterTest.kt`, delete the 10 `saveFlow …` tests added in Task 1.

- [ ] **Step 3: Add one shard-index integration test in the CLI test file**

Append this single test inside `class TestDebugReporterTest { ... }`:

```kotlin
    @Test
    fun `saveFlow with shardIndex 2 produces shard-3 prefixed filenames via facade`() {
        val outputDir = Files.createDirectories(tempDir.resolve("out"))
        val shot = Files.createFile(tempDir.resolve("raw.png")).toFile()
        val cmd = maestro.orchestra.MaestroCommand(tapOnElement = null)
        val debug = FlowDebugOutput().apply {
            commands[cmd] = CommandDebugMetadata(status = CommandStatus.COMPLETED, timestamp = 1L)
            screenshots.add(FlowDebugOutput.Screenshot(shot, 555L, CommandStatus.COMPLETED))
        }

        TestDebugReporter.saveFlow("my_flow", debug, outputDir, shardIndex = 2)

        val names = outputDir.toFile().listFiles()!!.map { it.name }
        assertThat(names).contains("commands-shard-3-(my_flow).json")
        assertThat(names).contains("screenshot-shard-3-✅-555-(my_flow).png")
    }
```

The existing imports at the top of that test file already cover everything it needs (`FlowDebugOutput`, `CommandDebugMetadata`, `CommandStatus` — now resolved through the orchestra FQNs added during Tasks 2 and 3).

- [ ] **Step 4: Run both test suites**

```bash
cd /Users/amanjeetsingh/Desktop/Workspace/Maestro
./gradlew :maestro-orchestra:test :maestro-cli:test
```

Expected: BUILD SUCCESSFUL. `TestOutputWriterTest` passes with 10 tests; `TestDebugReporterTest` passes with its lifecycle tests + the one new shard-index facade test.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "test: migrate saveFlow tests to orchestra, keep shard facade test in CLI

10 direct saveFlow tests now live in
maestro-orchestra/src/test/.../TestOutputWriterTest.kt and exercise
TestOutputWriter.saveFlow directly with filenamePrefix / logPrefix args.
CLI retains one test proving TestDebugReporter.saveFlow translates
shardIndex correctly into the shard-N+1 filename prefix."
```

---

### Task 7: Manual CLI byte-identical verification

**Goal:** Prove `maestro test --debug-output` output did not change.

- [ ] **Step 1: Run a known flow against `main` and capture output**

On a scratch worktree at `main`:

```bash
cd /tmp && git clone /Users/amanjeetsingh/Desktop/Workspace/Maestro maestro-main
cd maestro-main
./gradlew :maestro-cli:installDist
./maestro-cli/build/install/maestro/bin/maestro test --debug-output /tmp/debug-main \
    <path-to-any-short-passing-and-one-failing-flow>
```

Record directory listing and file hashes:

```bash
find /tmp/debug-main -type f | sort > /tmp/main-files.txt
find /tmp/debug-main -type f -exec md5 {} \; > /tmp/main-hashes.txt
```

- [ ] **Step 2: Run the same flow against the feature branch**

```bash
cd /Users/amanjeetsingh/Desktop/Workspace/Maestro
./gradlew :maestro-cli:installDist
./maestro-cli/build/install/maestro/bin/maestro test --debug-output /tmp/debug-feat <same-flow>

find /tmp/debug-feat -type f | sort > /tmp/feat-files.txt
find /tmp/debug-feat -type f -exec md5 {} \; > /tmp/feat-hashes.txt
```

- [ ] **Step 3: Diff the two runs**

```bash
# Normalize the prefix path before diffing
sed 's|/tmp/debug-main|/tmp/debug|g' /tmp/main-files.txt > /tmp/main-files.norm.txt
sed 's|/tmp/debug-feat|/tmp/debug|g' /tmp/feat-files.txt > /tmp/feat-files.norm.txt
diff /tmp/main-files.norm.txt /tmp/feat-files.norm.txt

# Hash diff (filename column comparison — timestamps embedded in filenames will differ,
# which is expected and benign)
```

Expected: identical filename *shapes*. Timestamps inside filenames will differ between runs — that's normal. What must be identical:
- The set of files per flow (`commands-*.json` present; screenshot files with same emoji tags).
- JSON structure of `commands-*.json` (run `jq keys` on each to verify).
- `maestro.log` still created in the session folder.

- [ ] **Step 4: If any structural diff, investigate and fix before proceeding**

If no structural diff, the Maestro-side work is done. Push the branch.

```bash
git push origin feat/test-output-shared
```

Open PR on Maestro, request review. Do NOT merge yet — Part B's copilot branch will pin the Maestro submodule to the merge commit after merge. If we need a pre-merge pin, the copilot branch can pin the branch SHA temporarily.

---

## Part B — Copilot repo (`feat/cloud-debug-artifacts` branch)

### Task 8: Create branch in copilot and bump Maestro submodule

**Files:**
- Modify: `.gitmodules` (no change) and the `Maestro/` submodule pointer inside `/Users/amanjeetsingh/Desktop/Workspace/copilot/`.

Note: the copilot repo's Maestro submodule path may not be at `Maestro/` — verify with `git submodule status` before running `cd` into a submodule path.

- [ ] **Step 1: Inspect submodule**

```bash
cd /Users/amanjeetsingh/Desktop/Workspace/copilot
git submodule status
```

Record the path of the Maestro submodule (call it `$MAESTRO_SUBMODULE_PATH`, e.g. `maestro`, `Maestro`, or similar).

- [ ] **Step 2: Create branch**

```bash
git checkout -b feat/cloud-debug-artifacts
```

- [ ] **Step 3: Update submodule pointer**

```bash
cd $MAESTRO_SUBMODULE_PATH
git fetch origin feat/test-output-shared
git checkout feat/test-output-shared   # points at the Maestro branch tip
cd -
git add $MAESTRO_SUBMODULE_PATH
git status
```

Verify the status shows a modified submodule reference.

- [ ] **Step 4: Build worker against the new Maestro to catch any compile error before writing tests**

```bash
cd /Users/amanjeetsingh/Desktop/Workspace/copilot/maestro-worker
./gradlew compileKotlin
```

Expected: BUILD SUCCESSFUL. If it fails because `WorkerOrchestraState` or `MaestroTestRunner` references something that moved in the Maestro PR (e.g., `CommandStatus` — though worker uses its own `dev.mobile.maestro.worker.api.CommandStatus`, not the CLI one), fix the references here.

- [ ] **Step 5: Commit the submodule bump**

```bash
cd /Users/amanjeetsingh/Desktop/Workspace/copilot
git commit -m "chore: bump Maestro submodule to feat/test-output-shared

Pulls in the TestOutputWriter extraction in maestro-orchestra; no worker
code changes yet. Next commits add the worker-side integration."
```

---

### Task 9: Add `FlowDebugOutput` field and populate on command start (TDD)

**Files:**
- Modify: `/Users/amanjeetsingh/Desktop/Workspace/copilot/maestro-worker/src/main/kotlin/dev/mobile/maestro/worker/WorkerOrchestraState.kt`
- Modify: `/Users/amanjeetsingh/Desktop/Workspace/copilot/maestro-worker/src/test/kotlin/dev/mobile/maestro/worker/WorkerOrchestraStateTest.kt`

- [ ] **Step 1: Write failing test**

Append to `WorkerOrchestraStateTest.kt` (inside the class):

```kotlin
    @Test
    fun `onCommandStart populates flowDebugOutput with command metadata`() {
        val cmd = MaestroCommand(tapOnElement = null)

        orchestraState.onCommandStart(cmd)

        val meta = orchestraState.flowDebugOutput.commands[cmd]
        assertThat(meta).isNotNull()
        assertThat(meta!!.sequenceNumber).isEqualTo(0)
        assertThat(meta.timestamp).isNotNull()
    }
```

Ensure the test file has these imports at the top:

```kotlin
import maestro.orchestra.debug.FlowDebugOutput
```

- [ ] **Step 2: Run test — expect compile error**

```bash
cd /Users/amanjeetsingh/Desktop/Workspace/copilot/maestro-worker
./gradlew test --tests dev.mobile.maestro.worker.WorkerOrchestraStateTest.'onCommandStart populates flowDebugOutput with command metadata'
```

Expected: BUILD FAILED with "unresolved reference: flowDebugOutput".

- [ ] **Step 3: Add the field and populate it in `onCommandStart`**

Edit `WorkerOrchestraState.kt`. Add at the top:

```kotlin
import maestro.orchestra.debug.CommandDebugMetadata
import maestro.orchestra.debug.CommandStatus as OrchestraCommandStatus
import maestro.orchestra.debug.FlowDebugOutput
```

Note the alias: the worker already has `dev.mobile.maestro.worker.api.CommandStatus`, so we alias the orchestra one to avoid collision.

Add a new property on `WorkerOrchestraState`, right after the existing `commandMetadata` map declaration:

```kotlin
    /**
     * Debug output for this flow, written to disk by MaestroTestRunner.storeDebugOutput
     * at the end of the run and uploaded to RUN_ARTIFACTS under jobPaths.artifactsFolder.
     */
    val flowDebugOutput = FlowDebugOutput()
```

Inside `onCommandStart`, after `commandStartTimes[index] = startedAt`, add:

```kotlin
            flowDebugOutput.commands[maestroCommand] = CommandDebugMetadata(
                sequenceNumber = index,
                timestamp = startedAt,
            )
```

- [ ] **Step 4: Run test — expect PASS**

```bash
./gradlew test --tests dev.mobile.maestro.worker.WorkerOrchestraStateTest.'onCommandStart populates flowDebugOutput with command metadata'
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
cd /Users/amanjeetsingh/Desktop/Workspace/copilot
git add maestro-worker/src/main/kotlin/dev/mobile/maestro/worker/WorkerOrchestraState.kt \
        maestro-worker/src/test/kotlin/dev/mobile/maestro/worker/WorkerOrchestraStateTest.kt
git commit -m "feat(worker): start populating FlowDebugOutput on command start

WorkerOrchestraState gains a public flowDebugOutput field that
MaestroTestRunner will read at the end of the run. onCommandStart
initializes a CommandDebugMetadata per command with sequence number
and timestamp; terminal status is set by subsequent callbacks."
```

---

### Task 10: Populate status and duration on terminal callbacks (TDD)

**Files:**
- Modify: `/Users/amanjeetsingh/Desktop/Workspace/copilot/maestro-worker/src/main/kotlin/dev/mobile/maestro/worker/WorkerOrchestraState.kt`
- Modify: `/Users/amanjeetsingh/Desktop/Workspace/copilot/maestro-worker/src/test/kotlin/dev/mobile/maestro/worker/WorkerOrchestraStateTest.kt`

- [ ] **Step 1: Write failing tests for COMPLETED, SKIPPED, WARNED**

Append to `WorkerOrchestraStateTest.kt`:

```kotlin
    @Test
    fun `onCommandComplete sets flowDebugOutput status to COMPLETED with duration`() {
        val cmd = MaestroCommand(tapOnElement = null)

        orchestraState.onCommandStart(cmd)
        Thread.sleep(5)
        orchestraState.onCommandComplete(cmd)

        val meta = orchestraState.flowDebugOutput.commands[cmd]!!
        assertThat(meta.status).isEqualTo(OrchestraCommandStatus.COMPLETED)
        assertThat(meta.duration!!).isAtLeast(0L)
    }

    @Test
    fun `onCommandSkipped sets flowDebugOutput status to SKIPPED`() {
        val cmd = MaestroCommand(tapOnElement = null)

        orchestraState.onCommandStart(cmd)
        orchestraState.onCommandSkipped(cmd)

        val meta = orchestraState.flowDebugOutput.commands[cmd]!!
        assertThat(meta.status).isEqualTo(OrchestraCommandStatus.SKIPPED)
    }

    @Test
    fun `onCommandWarned sets flowDebugOutput status to WARNED`() {
        val cmd = MaestroCommand(tapOnElement = null)

        orchestraState.onCommandStart(cmd)
        orchestraState.onCommandWarned(cmd)

        val meta = orchestraState.flowDebugOutput.commands[cmd]!!
        assertThat(meta.status).isEqualTo(OrchestraCommandStatus.WARNED)
    }
```

Add at the top of the test file:

```kotlin
import maestro.orchestra.debug.CommandStatus as OrchestraCommandStatus
```

- [ ] **Step 2: Run — expect FAIL**

```bash
./gradlew test --tests dev.mobile.maestro.worker.WorkerOrchestraStateTest
```

Expected: the three new tests fail because `meta.status` is null.

- [ ] **Step 3: Populate status in `handleTerminalCommand`**

In `WorkerOrchestraState.handleTerminalCommand(...)`, right after the existing `val (index, depthIndex) = indexTracker.onCommandFinished(maestroCommand)` line, add:

```kotlin
        flowDebugOutput.commands[maestroCommand]?.apply {
            this.status = when (status) {
                CommandStatus.COMPLETED -> OrchestraCommandStatus.COMPLETED
                CommandStatus.FAILED -> OrchestraCommandStatus.FAILED
                CommandStatus.SKIPPED -> OrchestraCommandStatus.SKIPPED
                CommandStatus.WARNED -> OrchestraCommandStatus.WARNED
                else -> OrchestraCommandStatus.PENDING
            }
            calculateDuration()
        }
```

(`CommandStatus` here is the worker's `dev.mobile.maestro.worker.api.CommandStatus` — already imported.)

- [ ] **Step 4: Run — expect PASS**

```bash
./gradlew test --tests dev.mobile.maestro.worker.WorkerOrchestraStateTest
```

Expected: all three new tests pass.

- [ ] **Step 5: Commit**

```bash
git add maestro-worker/src/main/kotlin/dev/mobile/maestro/worker/WorkerOrchestraState.kt \
        maestro-worker/src/test/kotlin/dev/mobile/maestro/worker/WorkerOrchestraStateTest.kt
git commit -m "feat(worker): populate terminal status and duration in flowDebugOutput

handleTerminalCommand now maps the worker's CommandStatus into the
orchestra CommandStatus and records it on flowDebugOutput.commands
along with duration (via calculateDuration)."
```

---

### Task 11: Capture fresh failure screenshot + hierarchy (TDD)

**Files:**
- Modify: `/Users/amanjeetsingh/Desktop/Workspace/copilot/maestro-worker/src/main/kotlin/dev/mobile/maestro/worker/WorkerOrchestraState.kt`
- Modify: `/Users/amanjeetsingh/Desktop/Workspace/copilot/maestro-worker/src/test/kotlin/dev/mobile/maestro/worker/WorkerOrchestraStateTest.kt`

- [ ] **Step 1: Write failing tests**

Append to `WorkerOrchestraStateTest.kt`:

```kotlin
    @Test
    fun `onCommandFailed takes fresh screenshot and appends to flowDebugOutput screenshots`() {
        val cmd = MaestroCommand(tapOnElement = null)
        val fakePng = byteArrayOf(1, 2, 3, 4)
        every { mockDevice.takeScreenshot() } returns fakePng

        orchestraState.onCommandStart(cmd)
        orchestraState.onCommandFailed(cmd, RuntimeException("boom"))

        val shots = orchestraState.flowDebugOutput.screenshots
        assertThat(shots).hasSize(1)
        assertThat(shots[0].status).isEqualTo(OrchestraCommandStatus.FAILED)
        assertThat(shots[0].screenshot.readBytes()).isEqualTo(fakePng)
    }

    @Test
    fun `onCommandFailed records error on flowDebugOutput metadata`() {
        val cmd = MaestroCommand(tapOnElement = null)
        every { mockDevice.takeScreenshot() } returns byteArrayOf()

        orchestraState.onCommandStart(cmd)
        orchestraState.onCommandFailed(cmd, RuntimeException("boom"))

        val meta = orchestraState.flowDebugOutput.commands[cmd]!!
        assertThat(meta.status).isEqualTo(OrchestraCommandStatus.FAILED)
        assertThat(meta.error).isNotNull()
        assertThat(meta.error!!.message).isEqualTo("boom")
    }

    @Test
    fun `onCommandComplete does not add a screenshot to flowDebugOutput`() {
        val cmd = MaestroCommand(tapOnElement = null)

        orchestraState.onCommandStart(cmd)
        orchestraState.onCommandComplete(cmd)

        assertThat(orchestraState.flowDebugOutput.screenshots).isEmpty()
    }
```

- [ ] **Step 2: Run — expect FAIL**

```bash
./gradlew test --tests dev.mobile.maestro.worker.WorkerOrchestraStateTest
```

Expected: the three new tests fail.

- [ ] **Step 3: Implement fresh-screenshot-on-failure path**

In `WorkerOrchestraState.kt`, modify the `flowDebugOutput.commands[maestroCommand]?.apply { ... }` block added in Task 10 so that on FAILED:

1. Sets `error` from `errorMessage` wrapping (we need to pass the original `throwable` down).
2. Appends a fresh `FlowDebugOutput.Screenshot` to `flowDebugOutput.screenshots`.

Because `handleTerminalCommand` does not currently have access to the `throwable`, change its signature to accept an optional `throwable: Throwable? = null` and thread it through:

```kotlin
    private fun handleTerminalCommand(
        maestroCommand: MaestroCommand,
        status: CommandStatus,
        errorMessage: String? = null,
        captureHierarchy: Boolean = false,
        throwable: Throwable? = null,
    ) {
```

Update the call in `onCommandFailed` to pass the throwable:

```kotlin
    fun onCommandFailed(maestroCommand: MaestroCommand, throwable: Throwable) {
        logger.error("Command failed", throwable)
        handleTerminalCommand(
            maestroCommand,
            CommandStatus.FAILED,
            errorMessage = throwable.message ?: "Unknown error",
            captureHierarchy = true,
            throwable = throwable,
        )
    }
```

Inside `handleTerminalCommand`, extend the `flowDebugOutput.commands[maestroCommand]?.apply { ... }` block to record error and hierarchy, and capture the failure screenshot:

```kotlin
        flowDebugOutput.commands[maestroCommand]?.apply {
            this.status = when (status) {
                CommandStatus.COMPLETED -> OrchestraCommandStatus.COMPLETED
                CommandStatus.FAILED -> OrchestraCommandStatus.FAILED
                CommandStatus.SKIPPED -> OrchestraCommandStatus.SKIPPED
                CommandStatus.WARNED -> OrchestraCommandStatus.WARNED
                else -> OrchestraCommandStatus.PENDING
            }
            this.error = throwable
            if (captureHierarchy) {
                try {
                    this.hierarchy = device.getMaestro().viewHierarchy().root
                } catch (e: Exception) {
                    logger.warn("Failed to attach hierarchy to flowDebugOutput for index $index", e)
                }
            }
            calculateDuration()
        }

        if (status == CommandStatus.FAILED) {
            try {
                val bytes = device.takeScreenshot()
                val file = java.nio.file.Files.createTempFile("maestro-fail-", ".png").toFile()
                file.writeBytes(bytes)
                flowDebugOutput.screenshots.add(
                    FlowDebugOutput.Screenshot(
                        screenshot = file,
                        timestamp = completedAt,
                        status = OrchestraCommandStatus.FAILED,
                    )
                )
            } catch (e: Exception) {
                logger.warn("Failed to capture failure screenshot for flowDebugOutput", e)
            }
        }
```

- [ ] **Step 4: Run — expect PASS**

```bash
./gradlew test --tests dev.mobile.maestro.worker.WorkerOrchestraStateTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add maestro-worker/src/main/kotlin/dev/mobile/maestro/worker/WorkerOrchestraState.kt \
        maestro-worker/src/test/kotlin/dev/mobile/maestro/worker/WorkerOrchestraStateTest.kt
git commit -m "feat(worker): capture fresh failure screenshot and hierarchy for debug output

On FAILED terminal callback, WorkerOrchestraState now takes a dedicated
device.takeScreenshot() and writes it to a temp file, appending a
FlowDebugOutput.Screenshot with status=FAILED. Also attaches the view
hierarchy TreeNode to CommandDebugMetadata. Both failures in the capture
path are swallowed with a warning so they never mask the test failure."
```

---

### Task 12: Add `storeDebugOutput` to `MaestroTestRunner` (TDD)

**Files:**
- Modify: `/Users/amanjeetsingh/Desktop/Workspace/copilot/maestro-worker/src/main/kotlin/dev/mobile/maestro/worker/maestro/MaestroTestRunner.kt`
- Modify or Create: worker test file — verify path with `find ... MaestroTestRunner*Test*`. If none exists yet, create one at `maestro-worker/src/test/kotlin/dev/mobile/maestro/worker/maestro/MaestroTestRunnerTest.kt` for this narrow unit test.

- [ ] **Step 1: Check for existing test file and create if absent**

```bash
find /Users/amanjeetsingh/Desktop/Workspace/copilot/maestro-worker/src/test -name "MaestroTestRunner*"
```

If the command returns nothing, create `maestro-worker/src/test/kotlin/dev/mobile/maestro/worker/maestro/MaestroTestRunnerTest.kt` with the test below. Otherwise, append the test to the existing file.

- [ ] **Step 2: Write failing test**

```kotlin
package dev.mobile.maestro.worker.maestro

import com.google.common.truth.Truth.assertThat
import dev.mobile.maestro.worker.WorkerOrchestraState
import dev.mobile.maestro.worker.api.JobPaths
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import maestro.orchestra.MaestroCommand
import maestro.orchestra.debug.CommandDebugMetadata
import maestro.orchestra.debug.CommandStatus
import org.junit.jupiter.api.Test
import storage.FileStorage
import storage.FileStorageType

class MaestroTestRunnerStoreDebugOutputTest {

    @Test
    fun `storeDebugOutput writes commands JSON into artifactsFolder`() {
        val fileStorage = mockk<FileStorage>(relaxed = true)
        val jobPaths = JobPaths(
            screenRecording = "job-abc.mp4",
            artifactsFolder = "job-abc/",
            logs = "job-abc/logs.zip",
            hierarchy = "job-abc/hierarchy.json",
            screenshotPrefix = "job-abc_",
            crashReport = "job-abc/app-crash-report.txt",
            anrReport = "job-abc/app-anr-report.txt",
        )
        val orchestraState = mockk<WorkerOrchestraState>(relaxed = true) {
            every { flowDebugOutput } returns maestro.orchestra.debug.FlowDebugOutput().apply {
                commands[MaestroCommand(tapOnElement = null)] =
                    CommandDebugMetadata(status = CommandStatus.COMPLETED, timestamp = 1L)
            }
        }

        val uploadedNames = mutableListOf<String>()
        every {
            fileStorage.create(FileStorageType.RUN_ARTIFACTS, any<java.io.File>(), capture(slot<String>()))
        } answers {
            uploadedNames.add(arg(2))
        }

        MaestroTestRunnerDebugHelper.storeDebugOutput(
            fileStorage = fileStorage,
            jobPaths = jobPaths,
            flowName = "job-abc",
            orchestraState = orchestraState,
        )

        assertThat(uploadedNames).contains("job-abc/commands-(job-abc).json")
    }
}
```

(The helper target `MaestroTestRunnerDebugHelper.storeDebugOutput` is a top-level or companion-level function extracted to keep this unit testable without standing up an entire `MaestroTestRunner`. Task 13 wires it into `MaestroTestRunner.collectAllArtifacts`.)

- [ ] **Step 3: Run — expect FAIL (unresolved reference)**

```bash
cd /Users/amanjeetsingh/Desktop/Workspace/copilot/maestro-worker
./gradlew test --tests dev.mobile.maestro.worker.maestro.MaestroTestRunnerStoreDebugOutputTest
```

Expected: BUILD FAILED — "Unresolved reference: MaestroTestRunnerDebugHelper".

- [ ] **Step 4: Implement `storeDebugOutput`**

Create the helper and call site. Open `maestro-worker/src/main/kotlin/dev/mobile/maestro/worker/maestro/MaestroTestRunner.kt` and add, at the bottom of the file (outside the `class MaestroTestRunner`):

```kotlin
object MaestroTestRunnerDebugHelper {
    private val logger = org.slf4j.LoggerFactory.getLogger(MaestroTestRunnerDebugHelper::class.java)

    fun storeDebugOutput(
        fileStorage: storage.FileStorage,
        jobPaths: dev.mobile.maestro.worker.api.JobPaths,
        flowName: String,
        orchestraState: dev.mobile.maestro.worker.WorkerOrchestraState,
    ) {
        val debugDir = java.nio.file.Files.createTempDirectory("maestro-debug-")
        try {
            maestro.orchestra.debug.TestOutputWriter.saveFlow(
                path = debugDir,
                flowName = flowName,
                debugOutput = orchestraState.flowDebugOutput,
            )
            debugDir.toFile().listFiles()?.forEach { file ->
                fileStorage.create(
                    storage.FileStorageType.RUN_ARTIFACTS,
                    file,
                    "${jobPaths.artifactsFolder}${file.name}",
                )
            }
        } catch (e: Exception) {
            logger.warn("Failed to store debug output", e)
        } finally {
            debugDir.toFile().deleteRecursively()
        }
    }
}
```

- [ ] **Step 5: Run — expect PASS**

```bash
./gradlew test --tests dev.mobile.maestro.worker.maestro.MaestroTestRunnerStoreDebugOutputTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add maestro-worker/src/main/kotlin/dev/mobile/maestro/worker/maestro/MaestroTestRunner.kt \
        maestro-worker/src/test/kotlin/dev/mobile/maestro/worker/maestro/MaestroTestRunnerStoreDebugOutputTest.kt
git commit -m "feat(worker): MaestroTestRunnerDebugHelper.storeDebugOutput

Writes flowDebugOutput into a temp dir via TestOutputWriter.saveFlow,
then uploads each resulting file flat into jobPaths.artifactsFolder.
Extracted as an object helper so it is testable without standing up a
full MaestroTestRunner. Next task wires it into collectAllArtifacts."
```

---

### Task 13: Wire `storeDebugOutput` into `collectAllArtifacts`

**Files:**
- Modify: `/Users/amanjeetsingh/Desktop/Workspace/copilot/maestro-worker/src/main/kotlin/dev/mobile/maestro/worker/maestro/MaestroTestRunner.kt`

- [ ] **Step 1: Add a constructor param for `orchestraState`**

Today `MaestroTestRunner` has no reference to `WorkerOrchestraState`. Add one so `collectAllArtifacts` can reach `flowDebugOutput`. Near the existing constructor, add a parameter:

```kotlin
class MaestroTestRunner(
    private val apiClient: WorkerApiClient,
    private val jobId: String,
    private val orchestra: Orchestra,
    private val fileStorage: FileStorage,
    private val device: Device,
    private val jobPaths: JobPaths,
    private val platform: Platform,
    private val orchestraState: WorkerOrchestraState,       // NEW
    private val screenshotsDir: Path? = null,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val watchdogTimeoutMs: Long = DEFAULT_WATCHDOG_TIMEOUT_MS,
    private val tempFileHandler: TempFileHandler = TempFileHandler(),
    private val metricsServer: MetricsServer? = null,
) {
```

Update every `MaestroTestRunner(...)` construction site in the copilot repo to pass the already-constructed `WorkerOrchestraState`. Use grep:

```bash
cd /Users/amanjeetsingh/Desktop/Workspace/copilot
grep -rn "MaestroTestRunner(" --include="*.kt"
```

Each caller already constructs both — add the new arg.

- [ ] **Step 2: Call `storeDebugOutput` from `collectAllArtifacts`**

Edit `MaestroTestRunner.collectAllArtifacts`:

```kotlin
    private suspend fun collectAllArtifacts() {
        storeScreenRecording()
        storeArtifacts()
        storeScreenshots()
        MaestroTestRunnerDebugHelper.storeDebugOutput(
            fileStorage = fileStorage,
            jobPaths = jobPaths,
            flowName = jobId,
            orchestraState = orchestraState,
        )
    }
```

Using `jobId` as the flow name — there is no clean flow name available in `JobPayload`; this yields `commands-({jobId}).json`.

- [ ] **Step 3: Build**

```bash
cd /Users/amanjeetsingh/Desktop/Workspace/copilot/maestro-worker
./gradlew compileKotlin
```

Expected: BUILD SUCCESSFUL. If any caller construction site was missed, fix it now.

- [ ] **Step 4: Run full worker test suite**

```bash
./gradlew test
```

Expected: BUILD SUCCESSFUL. Any `MaestroTestRunner` test that constructs the runner needs updating to pass the new `orchestraState` arg — do so, using `mockk<WorkerOrchestraState>(relaxed = true)`.

- [ ] **Step 5: Commit**

```bash
cd /Users/amanjeetsingh/Desktop/Workspace/copilot
git add -A
git commit -m "feat(worker): wire storeDebugOutput into collectAllArtifacts

MaestroTestRunner now takes a WorkerOrchestraState in its constructor
and calls MaestroTestRunnerDebugHelper.storeDebugOutput at the end of
every run (success or failure), uploading commands-({jobId}).json plus
any failure screenshot into jobPaths.artifactsFolder."
```

---

### Task 14: End-to-end smoke test against a staging worker

- [ ] **Step 1: Deploy the branch to staging (or spin up worker locally pointed at staging)**

Follow the copilot repo's standard deploy/boot procedure for a worker branch.

- [ ] **Step 2: Submit a short passing flow**

Run a single-step flow that passes. After completion, check `RUN_ARTIFACTS` at `$jobId/`.

Expected contents include:
- `commands-($jobId).json` — valid JSON, non-empty, contains one entry per command, each with `status: COMPLETED`.
- No `screenshot-❌-...` file.
- Existing artifacts (`logs.zip`, `hierarchy.json` if applicable) unchanged.

- [ ] **Step 3: Submit a short failing flow** (e.g., one that asserts visibility of a non-existent element)

Expected contents at `$jobId/`:
- `commands-($jobId).json` — contains the failing command with `status: FAILED`, `error` object populated, `hierarchy` tree attached.
- `screenshot-❌-{timestamp}-($jobId).png` — a valid PNG showing the device state at the moment of failure.

- [ ] **Step 4: Push and open PR**

```bash
cd /Users/amanjeetsingh/Desktop/Workspace/copilot
git push origin feat/cloud-debug-artifacts
```

Open PR. In the description, note the Maestro submodule dependency: PR cannot merge until the Maestro PR merges and the submodule points to the Maestro merge commit (re-run Task 8 step 3 after Maestro merges, amend the submodule-bump commit, force-push branch).

---

## Self-review checklist

Run after the plan is drafted — fix any issues inline.

**Spec coverage:**
- Part 1 of spec (Maestro-side) → Tasks 1–7 ✓
- Part 2 of spec (copilot-side) → Tasks 8–14 ✓
- Step 1.0 of spec (tests-first migration) → Task 1 ✓
- Step 1.1 of spec (relocate types) → Tasks 2 + 3 ✓
- Step 1.2 of spec (add TestOutputWriter) → Task 4 ✓
- Step 1.3 of spec (CLI facade) → Task 5 ✓
- Step 1.4 of spec (import-site cleanup) → folded into Tasks 2 and 3 ✓
- Step 1.5 of spec (verification) → Task 7 ✓
- Step 2.1 (bump submodule) → Task 8 ✓
- Step 2.2 (WorkerOrchestraState populates FlowDebugOutput) → Tasks 9, 10, 11 ✓
- Step 2.3 (storeDebugOutput) → Task 12 ✓
- Step 2.4 (flow name) → addressed in Task 13 (uses `jobId`) ✓
- Step 2.5 (verification) → Task 14 ✓

**Placeholder scan:** no TBDs, all code shown in full, all commands provided. Task 8 Step 1 explicitly asks the engineer to verify the submodule path via `git submodule status` before assuming — that's a safety check, not a placeholder.

**Type consistency:**
- `TestOutputWriter.saveFlow(path, flowName, debugOutput, filenamePrefix, logPrefix)` — same parameter order in Task 4's definition, Task 5's CLI delegation, Task 6's orchestra tests, and Task 12's worker helper call. ✓
- `CommandStatus` uses the alias `OrchestraCommandStatus` consistently in the worker test and implementation (Tasks 9–11). ✓
- `FlowDebugOutput.Screenshot(screenshot, timestamp, status)` parameter order matches the data class defined in Task 3. ✓
- `MaestroTestRunnerDebugHelper.storeDebugOutput(fileStorage, jobPaths, flowName, orchestraState)` — same signature in Task 12's test, Task 12's implementation, and Task 13's call site. ✓

**Known unknowns flagged explicitly in plan (not placeholders):**
- Copilot submodule path (Task 8 Step 1) — engineer runs `git submodule status` to discover.
- Existence of `MaestroTestRunner` test file in copilot (Task 12 Step 1) — engineer runs `find` to check.
- `MaestroTestRunner` construction sites (Task 13 Step 1) — engineer greps to find them all.

These are real discoverability steps in an unfamiliar codebase, not deferred implementation.
