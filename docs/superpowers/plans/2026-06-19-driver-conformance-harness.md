# Driver Conformance Harness — Implementation Plan (Phase 1: Core + Native fixture)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the driver-conformance harness inside `maestro-client` and prove it end-to-end against a **native Android** fixture — first on one API level, then all supported levels — so every `AndroidDriver` Tier-A command is behavior-verified via an out-of-band logcat oracle.

**Architecture:** A single-device, sequential runner. A `DeviceProvider` hands back one fresh AVD; a `LogcatEventReader` tails `adb logcat -s MAESTRO_FIXTURE` and parses `FixtureEvent(epoch, seq, type, payload)`; a `CommandBehavior` per command does arrange→baseline→act→verify using either an APP-event oracle (logcat) or a RET/PROBE oracle (driver return value / `pidof`); a `Reporter` writes per-command `command.json` + a static HTML matrix. The fixture is a native Android app that emits structured events via `android.util.Log.d("MAESTRO_FIXTURE", json)`.

**Tech Stack:** Kotlin/JVM (harness, in `maestro-client`), Clikt 4.2.2 (CLI, already in the version catalog), dadb 1.2.10, JUnit 5 + Google Truth (harness unit tests), Jackson (already a maestro-client dep, for JSON), native Android (Kotlin) for the fixture app, stock Android SDK tooling (`sdkmanager`/`avdmanager`/`emulator`/`adb`) for provisioning.

## Global Constraints

- **Spec:** `docs/superpowers/specs/2026-06-19-driver-conformance-harness-design.md` — this plan implements Phase 1 of §10 against §4 (lifecycle/catalogue), §5 (contract), §6 (provisioning), §8 (reporting).
- **Strictly one device at a time.** No multi-device mode, no `--max-devices` flag, ever (§7).
- **No dependency on `maestro-device`.** Provisioning uses only stock Android SDK tools (§6).
- **Harness lives in `maestro-client`** in a dedicated `conformance` source set; its Gradle task is **excluded from `test`/`check`** so `./gradlew test` never runs it (§9).
- **Fixtures are separate Android app modules** under `fixtures/` (a fixture is a UI app; it cannot live in the JVM source set). This is *not* the "no new module" rule, which was about harness/shared code. **Confirm with the user before creating the first fixture module (Task 9).**
- **Attribution is `(epoch, seq)`**, never bare `seq` (§5/B5). `seq` resets to 1 on cold start / `pm clear`; `epoch` is a fresh random per process start.
- **Tag is real:** fixtures emit via native `android.util.Log.d("MAESTRO_FIXTURE", json)`; never `debugPrint`/`console.log` (B1). For the native fixture this is a direct call.
- **Supported API range: 24–36.**
- **Commit after every green step.** TDD: failing test → minimal code → green → commit.
- **No Co-Authored-By trailer in commits.**
- Work on branch `spec/driver-conformance-harness` (or a child branch); do not commit to `main`.

---

## File Structure

Harness (JVM) — all under `maestro-client/src/conformance/kotlin/maestro/conformance/`:

| File | Responsibility |
|---|---|
| `cli/ConformanceCli.kt` | Clikt entrypoint: parse `--api`, `--framework`, `--command`, `--device`, `--record`, `--out`; call the runner. |
| `cli/Selection.kt` | Parse `--api` lists/ranges (`24..36`, `25,26`) and `--framework`/`--command` lists into a typed selection. |
| `runner/ConformanceRunner.kt` | The nested loops (per API → per framework → per command), single device, wires provider+reader+behaviors+reporter. |
| `device/DeviceProvider.kt` | `interface DeviceProvider`, `DeviceSpec`, `DeviceHandle`. |
| `device/FreshAvdProvider.kt` | Create+boot+wait+teardown one fresh AVD via stock SDK; single fixed port; cleanup. |
| `device/AttachedDeviceProvider.kt` | Explicit BYO serial (`--device`), with banner. |
| `device/Preflight.kt` | Env checks (adb, SDK, system image, IME pin) — fail fast. |
| `device/Cmd.kt` | Thin `ProcessBuilder` wrapper: run a command, capture stdout/exit, timeout. |
| `logcat/FixtureEvent.kt` | `data class FixtureEvent(epoch, seq, type, payload)`. |
| `logcat/LogcatEventReader.kt` | Tail `adb logcat -s MAESTRO_FIXTURE`; parse; dedupe by `(epoch,seq)`; `eventsAfter(watermark, type, timeout)`. |
| `behavior/CommandBehavior.kt` | `interface CommandBehavior` + `BehaviorContext`. |
| `behavior/Oracle.kt` | `sealed Oracle` (APP_EVENT / RETURN_VALUE / DEVICE_PROBE) + `Verdict`. |
| `behavior/BehaviorRegistry.kt` | Map command-name → `CommandBehavior`; `coverage` classification. |
| `behavior/commands/TapBehavior.kt` (+ one file per command) | Per-command arrange/baseline/act/verify. |
| `report/CommandRecord.kt` | Serializable `command.json` shape. |
| `report/Reporter.kt` | Write per-command dir, `cell.json`, `summary.json`+`.js`, `index.html`. |

Fixture (Android app) — new module `fixtures/native/`:

| File | Responsibility |
|---|---|
| `fixtures/native/build.gradle.kts` | Android app, minSdk 24 / target 36 (mirror `maestro-android`). |
| `.../FixtureEmitter.kt` | `emit(type, payload)` → `Log.d("MAESTRO_FIXTURE", json)` with `epoch`+atomic `seq`. |
| `.../FixtureActivity.kt` | Single activity; routes to a screen by `route` launch arg / deep link; emits `SELFTEST` + `LIFECYCLE LAUNCHED(args)` on start. |
| `.../screens/TapScreen.kt` … | The §5.1 screens (native Views), each wired to emit its events. |

Gradle wiring: `maestro-client/build.gradle.kts` (add `conformance` source set + `driverConformance` JavaExec task), `settings.gradle.kts` (include the fixture module), `fixtures/native/build.gradle.kts` (copy-APK task → `maestro-client/src/conformance/resources/native-fixture.apk`).

---

## Phasing & review gates (matches the requested staging)

- **Stage 1 (Tasks 1–8):** harness skeleton + core (CLI, provider, reader, behavior framework, reporter) with JVM unit tests — no device needed.
- **Stage 2 (Tasks 9–12):** native fixture app + `tap` proven **on one API level** (first end-to-end green).
- **Stage 3 (Tasks 13–15):** remaining Tier-A commands on native, one API.
- **Stage 4 (Task 16):** run native fixture across **all** supported APIs (24–36); HTML matrix.
- **REVIEW GATE (Task 17):** human review of the complete native fixture + harness before any other fixture.
- **Stage 5 (separate plans):** Compose → RN → Flutter → WebView, one plan each, same shape.

---

## Task 1: Conformance source set + empty Clikt entrypoint (excluded from `test`)

**Files:**
- Modify: `maestro-client/build.gradle.kts` (add source set, configs, deps, task)
- Create: `maestro-client/src/conformance/kotlin/maestro/conformance/cli/ConformanceCli.kt`

**Interfaces:**
- Produces: a runnable `./gradlew :maestro-client:driverConformance --args="..."` whose main is `maestro.conformance.cli.ConformanceCliKt`.

- [ ] **Step 1: Add the `conformance` source set + task to `maestro-client/build.gradle.kts`**

Append at the end of the file:

```kotlin
// --- Driver Conformance Harness (excluded from test/check) ---
sourceSets {
    create("conformance") {
        compileClasspath += sourceSets["main"].output
        runtimeClasspath += sourceSets["main"].output
    }
}

val conformanceImplementation: Configuration by configurations.getting {
    extendsFrom(configurations["implementation"], configurations["api"])
}

dependencies {
    conformanceImplementation(libs.clikt)
    conformanceImplementation(libs.dadb)
}

tasks.register<JavaExec>("driverConformance") {
    group = "verification"
    description = "Run the driver conformance harness (device-backed; NOT part of check/test)."
    mainClass.set("maestro.conformance.cli.ConformanceCliKt")
    classpath = sourceSets["conformance"].runtimeClasspath
}
```

- [ ] **Step 2: Create the entrypoint**

`maestro-client/src/conformance/kotlin/maestro/conformance/cli/ConformanceCli.kt`:

```kotlin
package maestro.conformance.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option

class ConformanceCli : CliktCommand(name = "driver-conformance") {
    val api: String by option("--api", help = "API levels: list or range, e.g. 34 or 24..36").default("34")
    val framework: String by option("--framework", help = "Fixtures, e.g. native or all").default("native")
    val command: String? by option("--command", help = "Subset of commands; default all Tier A")
    val device: String? by option("--device", help = "BYO adb serial; skips provisioning")
    val record: String by option("--record", help = "all|on-failure|never").default("on-failure")
    val out: String by option("--out", help = "Report output dir").default("./report")

    override fun run() {
        echo("driver-conformance: api=$api framework=$framework record=$record out=$out")
    }
}

fun main(args: Array<String>) = ConformanceCli().main(args)
```

- [ ] **Step 3: Verify it compiles and runs (and is NOT in `check`)**

Run: `./gradlew :maestro-client:compileConformanceKotlin`
Expected: BUILD SUCCESSFUL.
Run: `./gradlew :maestro-client:driverConformance --args="--api 34 --framework native"`
Expected: prints `driver-conformance: api=34 framework=native record=on-failure out=./report`.
Run: `./gradlew :maestro-client:tasks --all | grep -i conformance` → `driverConformance` listed; confirm it is NOT a dependency of `check`/`test` (it isn't wired in).

- [ ] **Step 4: Commit**

```bash
git add maestro-client/build.gradle.kts maestro-client/src/conformance
git commit -m "feat(conformance): add conformance source set + Clikt entrypoint, excluded from check"
```

---

## Task 2: Selection parsing (`--api`, `--framework`, `--command`)

**Files:**
- Create: `maestro-client/src/conformance/kotlin/maestro/conformance/cli/Selection.kt`
- Test: `maestro-client/src/conformance/kotlin/maestro/conformance/cli/SelectionTest.kt` *(unit-testable, no device)*

**Interfaces:**
- Produces: `object Selection { fun parseApis(s: String): List<Int>; fun parseList(s: String): List<String> }`. APIs accept `34`, `25,26,27`, `24..36`; clamped to 24–36; sorted, de-duped.

> **Note on testing the conformance source set:** add a tiny test task so these pure-logic classes can be unit-tested without standing up a separate test source set. Append to `maestro-client/build.gradle.kts`:
> ```kotlin
> tasks.register<Test>("conformanceTest") {
>     description = "Unit tests for conformance harness logic (no device)."
>     testClassesDirs = sourceSets["conformance"].output.classesDirs
>     classpath = sourceSets["conformance"].runtimeClasspath
>     useJUnitPlatform()
> }
> dependencies {
>     conformanceImplementation(libs.junit.jupiter.api)
>     conformanceImplementation(libs.google.truth)
>     "conformanceRuntimeOnly"(libs.junit.jupiter.engine)
> }
> ```
> `conformanceTest` is **not** wired into `check`, preserving the isolation constraint.

- [ ] **Step 1: Write the failing test**

`SelectionTest.kt`:

```kotlin
package maestro.conformance.cli

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SelectionTest {
    @Test fun `parses single api`() {
        assertThat(Selection.parseApis("34")).containsExactly(34)
    }
    @Test fun `parses comma list`() {
        assertThat(Selection.parseApis("25,26,27")).containsExactly(25, 26, 27).inOrder()
    }
    @Test fun `parses range and clamps to 24-36`() {
        assertThat(Selection.parseApis("20..40")).isEqualTo((24..36).toList())
    }
    @Test fun `dedupes and sorts`() {
        assertThat(Selection.parseApis("30,24,30")).containsExactly(24, 30).inOrder()
    }
    @Test fun `parses framework list`() {
        assertThat(Selection.parseList("native,compose")).containsExactly("native", "compose").inOrder()
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :maestro-client:conformanceTest --tests "maestro.conformance.cli.SelectionTest"`
Expected: FAIL — `Selection` unresolved.

- [ ] **Step 3: Implement**

`Selection.kt`:

```kotlin
package maestro.conformance.cli

object Selection {
    private val SUPPORTED = 24..36

    fun parseApis(spec: String): List<Int> {
        val out = sortedSetOf<Int>()
        for (part in spec.split(",").map { it.trim() }.filter { it.isNotEmpty() }) {
            if (part.contains("..")) {
                val (lo, hi) = part.split("..").map { it.trim().toInt() }
                (lo..hi).forEach { if (it in SUPPORTED) out += it }
            } else {
                val v = part.toInt()
                if (v in SUPPORTED) out += v
            }
        }
        return out.toList()
    }

    fun parseList(spec: String): List<String> =
        spec.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :maestro-client:conformanceTest --tests "maestro.conformance.cli.SelectionTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add maestro-client/src/conformance maestro-client/build.gradle.kts
git commit -m "feat(conformance): selection parsing for --api/--framework/--command"
```

---

## Task 3: `Cmd` process runner

**Files:**
- Create: `maestro-client/src/conformance/kotlin/maestro/conformance/device/Cmd.kt`
- Test: `maestro-client/src/conformance/kotlin/maestro/conformance/device/CmdTest.kt`

**Interfaces:**
- Produces: `data class CmdResult(val exit: Int, val stdout: String, val stderr: String)` and
  `object Cmd { fun run(vararg args: String, timeoutMs: Long = 120_000): CmdResult }`.

- [ ] **Step 1: Write the failing test** (uses real `echo`/`false`, no device)

`CmdTest.kt`:

```kotlin
package maestro.conformance.device

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class CmdTest {
    @Test fun `captures stdout and zero exit`() {
        val r = Cmd.run("/bin/echo", "hello")
        assertThat(r.exit).isEqualTo(0)
        assertThat(r.stdout.trim()).isEqualTo("hello")
    }
    @Test fun `captures non-zero exit`() {
        val r = Cmd.run("/bin/sh", "-c", "exit 3")
        assertThat(r.exit).isEqualTo(3)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :maestro-client:conformanceTest --tests "maestro.conformance.device.CmdTest"`
Expected: FAIL — `Cmd` unresolved.

- [ ] **Step 3: Implement**

`Cmd.kt`:

```kotlin
package maestro.conformance.device

import java.util.concurrent.TimeUnit

data class CmdResult(val exit: Int, val stdout: String, val stderr: String) {
    val ok get() = exit == 0
}

object Cmd {
    fun run(vararg args: String, timeoutMs: Long = 120_000): CmdResult {
        val p = ProcessBuilder(*args).redirectErrorStream(false).start()
        val out = p.inputStream.bufferedReader()
        val err = p.errorStream.bufferedReader()
        val so = StringBuilder(); val se = StringBuilder()
        val tOut = Thread { out.forEachLine { so.appendLine(it) } }.apply { start() }
        val tErr = Thread { err.forEachLine { se.appendLine(it) } }.apply { start() }
        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            p.destroyForcibly()
            return CmdResult(124, so.toString(), se.toString() + "\n[timeout]")
        }
        tOut.join(2000); tErr.join(2000)
        return CmdResult(p.exitValue(), so.toString(), se.toString())
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :maestro-client:conformanceTest --tests "maestro.conformance.device.CmdTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add maestro-client/src/conformance
git commit -m "feat(conformance): Cmd process runner with timeout + stdout/stderr capture"
```

---

## Task 4: `FixtureEvent` + `LogcatEventReader` parsing & watermark (no device)

**Files:**
- Create: `maestro-client/src/conformance/kotlin/maestro/conformance/logcat/FixtureEvent.kt`
- Create: `maestro-client/src/conformance/kotlin/maestro/conformance/logcat/LogcatEventReader.kt`
- Test: `maestro-client/src/conformance/kotlin/maestro/conformance/logcat/LogcatEventReaderTest.kt`

**Interfaces:**
- Produces:
  - `data class FixtureEvent(val epoch: String, val seq: Int, val type: String, val payload: Map<String, Any?>)`
  - `data class Watermark(val epoch: String, val seq: Int)`
  - `class LogcatEventReader` with:
    - `fun ingest(rawLine: String)` — parse one logcat line; dedupe by `(epoch,seq)`.
    - `fun latestWatermark(): Watermark?` — highest `(epoch,seq)` currently buffered.
    - `fun eventsAfter(w: Watermark, type: String): List<FixtureEvent>` — same epoch, `seq > w.seq`, matching type.
  - The live tailing (spawning `adb logcat`) is added in Task 7; this task is pure parsing/buffer logic so it's unit-testable.

- [ ] **Step 1: Write the failing test**

`LogcatEventReaderTest.kt`:

```kotlin
package maestro.conformance.logcat

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class LogcatEventReaderTest {
    // A typical logcat line under `-s MAESTRO_FIXTURE` with threadtime format:
    private fun line(json: String) =
        "06-19 10:00:00.123  1234  1234 D MAESTRO_FIXTURE: $json"

    @Test fun `parses epoch seq type and payload`() {
        val r = LogcatEventReader()
        r.ingest(line("""{"epoch":"a1","seq":5,"event":"TAP","target":"tap_target","x":10,"y":20}"""))
        val w = r.latestWatermark()!!
        assertThat(w.epoch).isEqualTo("a1")
        assertThat(w.seq).isEqualTo(5)
        val ev = r.eventsAfter(Watermark("a1", 4), "TAP")
        assertThat(ev).hasSize(1)
        assertThat(ev[0].payload["target"]).isEqualTo("tap_target")
    }

    @Test fun `dedupes by epoch and seq`() {
        val r = LogcatEventReader()
        val l = line("""{"epoch":"a1","seq":5,"event":"TAP"}""")
        r.ingest(l); r.ingest(l)
        assertThat(r.eventsAfter(Watermark("a1", 4), "TAP")).hasSize(1)
    }

    @Test fun `eventsAfter ignores other epoch and lower seq and other type`() {
        val r = LogcatEventReader()
        r.ingest(line("""{"epoch":"a1","seq":4,"event":"TAP"}"""))   // below watermark
        r.ingest(line("""{"epoch":"b2","seq":9,"event":"TAP"}"""))   // other epoch
        r.ingest(line("""{"epoch":"a1","seq":6,"event":"SWIPE"}""")) // other type
        r.ingest(line("""{"epoch":"a1","seq":7,"event":"TAP"}"""))   // match
        val ev = r.eventsAfter(Watermark("a1", 5), "TAP")
        assertThat(ev.map { it.seq }).containsExactly(7)
    }

    @Test fun `ignores non-fixture and malformed lines`() {
        val r = LogcatEventReader()
        r.ingest("06-19 10:00:00.123 1234 1234 D OtherTag: hello")
        r.ingest(line("not-json"))
        assertThat(r.latestWatermark()).isNull()
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :maestro-client:conformanceTest --tests "maestro.conformance.logcat.LogcatEventReaderTest"`
Expected: FAIL — types unresolved.

- [ ] **Step 3: Implement**

`FixtureEvent.kt`:

```kotlin
package maestro.conformance.logcat

data class FixtureEvent(
    val epoch: String,
    val seq: Int,
    val type: String,
    val payload: Map<String, Any?>,
)

data class Watermark(val epoch: String, val seq: Int)
```

`LogcatEventReader.kt`:

```kotlin
package maestro.conformance.logcat

import com.fasterxml.jackson.databind.ObjectMapper

class LogcatEventReader {
    private val mapper = ObjectMapper()
    private val seen = HashSet<Pair<String, Int>>()
    private val events = ArrayList<FixtureEvent>()

    /** Parse a single logcat line. The fixture writes: `MAESTRO_FIXTURE: {json}`. */
    @Synchronized
    fun ingest(rawLine: String) {
        val marker = "MAESTRO_FIXTURE: "
        val idx = rawLine.indexOf(marker)
        if (idx < 0) return
        val json = rawLine.substring(idx + marker.length).trim()
        if (!json.startsWith("{")) return
        val node = try { mapper.readTree(json) } catch (e: Exception) { return }
        val epoch = node.get("epoch")?.asText() ?: return
        val seq = node.get("seq")?.takeIf { it.isInt }?.asInt() ?: return
        val type = node.get("event")?.asText() ?: return
        val key = epoch to seq
        if (!seen.add(key)) return
        val payload: Map<String, Any?> =
            mapper.convertValue(node, Map::class.java) as Map<String, Any?>
        events += FixtureEvent(epoch, seq, type, payload)
    }

    @Synchronized
    fun latestWatermark(): Watermark? =
        events.maxByOrNull { it.seq }?.let { Watermark(it.epoch, it.seq) }

    @Synchronized
    fun eventsAfter(w: Watermark, type: String): List<FixtureEvent> =
        events.filter { it.epoch == w.epoch && it.seq > w.seq && it.type == type }
            .sortedBy { it.seq }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :maestro-client:conformanceTest --tests "maestro.conformance.logcat.LogcatEventReaderTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add maestro-client/src/conformance
git commit -m "feat(conformance): FixtureEvent + LogcatEventReader parsing, dedupe, (epoch,seq) watermark"
```

---

## Task 5: Oracle + Verdict + `CommandBehavior` framework (no device)

**Files:**
- Create: `maestro-client/src/conformance/kotlin/maestro/conformance/behavior/Oracle.kt`
- Create: `maestro-client/src/conformance/kotlin/maestro/conformance/behavior/CommandBehavior.kt`
- Test: `maestro-client/src/conformance/kotlin/maestro/conformance/behavior/VerdictTest.kt`

**Interfaces:**
- Produces:
  - `enum class Coverage { FRAMEWORK_SENSITIVE, MIXED, DEVICE_LEVEL }`
  - `enum class OracleKind { APP_EVENT, RETURN_VALUE, DEVICE_PROBE }`
  - `data class Verdict(val pass: Boolean, val reason: String?)`
  - `class BehaviorContext(val driver: AndroidDriver, val reader: LogcatEventReader, val serial: String, val apiLevel: Int, val appId: String)` with helper `markWatermark(): Watermark` (sends a MARK request to the fixture and returns the resulting watermark — wired in Task 11; here it is declared).
  - `interface CommandBehavior { val name: String; val coverage: Coverage; fun run(ctx: BehaviorContext): CommandOutcome }`
  - `data class CommandOutcome(val verdict: Verdict, val oracleKind: OracleKind, val expected: Map<String, Any?>, val actual: Map<String, Any?>, val args: Map<String, Any?>)`

- [ ] **Step 1: Write the failing test** (pure value-type test)

`VerdictTest.kt`:

```kotlin
package maestro.conformance.behavior

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class VerdictTest {
    @Test fun `pass verdict has no reason`() {
        val v = Verdict.pass()
        assertThat(v.pass).isTrue()
        assertThat(v.reason).isNull()
    }
    @Test fun `fail verdict carries reason`() {
        val v = Verdict.fail("dir was DOWN, expected UP")
        assertThat(v.pass).isFalse()
        assertThat(v.reason).contains("expected UP")
    }
    @Test fun `outcome records oracle kind and fields`() {
        val o = CommandOutcome(
            verdict = Verdict.pass(),
            oracleKind = OracleKind.APP_EVENT,
            expected = mapOf("event" to "TAP"),
            actual = mapOf("event" to "TAP"),
            args = mapOf("point" to listOf(10, 20)),
        )
        assertThat(o.oracleKind).isEqualTo(OracleKind.APP_EVENT)
        assertThat(o.actual["event"]).isEqualTo("TAP")
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :maestro-client:conformanceTest --tests "maestro.conformance.behavior.VerdictTest"`
Expected: FAIL — types unresolved.

- [ ] **Step 3: Implement**

`Oracle.kt`:

```kotlin
package maestro.conformance.behavior

enum class Coverage { FRAMEWORK_SENSITIVE, MIXED, DEVICE_LEVEL }
enum class OracleKind { APP_EVENT, RETURN_VALUE, DEVICE_PROBE }

data class Verdict(val pass: Boolean, val reason: String?) {
    companion object {
        fun pass() = Verdict(true, null)
        fun fail(reason: String) = Verdict(false, reason)
    }
}

data class CommandOutcome(
    val verdict: Verdict,
    val oracleKind: OracleKind,
    val expected: Map<String, Any?>,
    val actual: Map<String, Any?>,
    val args: Map<String, Any?>,
)
```

`CommandBehavior.kt`:

```kotlin
package maestro.conformance.behavior

import maestro.conformance.logcat.LogcatEventReader
import maestro.conformance.logcat.Watermark
import maestro.drivers.AndroidDriver

class BehaviorContext(
    val driver: AndroidDriver,
    val reader: LogcatEventReader,
    val serial: String,
    val apiLevel: Int,
    val appId: String,
    /** Sends a MARK to the fixture and returns the resulting watermark. Wired in Task 11. */
    val markWatermark: () -> Watermark,
)

interface CommandBehavior {
    val name: String
    val coverage: Coverage
    fun run(ctx: BehaviorContext): CommandOutcome
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :maestro-client:conformanceTest --tests "maestro.conformance.behavior.VerdictTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add maestro-client/src/conformance
git commit -m "feat(conformance): Oracle/Verdict/CommandBehavior framework types"
```

---

## Task 6: `CommandRecord` + `Reporter` (command.json / cell.json / summary + HTML) (no device)

**Files:**
- Create: `maestro-client/src/conformance/kotlin/maestro/conformance/report/CommandRecord.kt`
- Create: `maestro-client/src/conformance/kotlin/maestro/conformance/report/Reporter.kt`
- Test: `maestro-client/src/conformance/kotlin/maestro/conformance/report/ReporterTest.kt`

**Interfaces:**
- Produces:
  - `data class CommandRecord(command, coverage, args, oracleKind, expected, actual, verdict, failureReason, actMs, totalMs)`
  - `class Reporter(rootDir: File)` with:
    - `fun writeCommand(cell: String, record: CommandRecord)` → `cells/<cell>/<command>/command.json`
    - `fun writeCell(cell: String, records: List<CommandRecord>)` → `cells/<cell>/cell.json`
    - `fun writeSummary(banner: String)` → `summary.json`, `summary.js`, `index.html` (matrix grid)

- [ ] **Step 1: Write the failing test**

`ReporterTest.kt`:

```kotlin
package maestro.conformance.report

import com.google.common.truth.Truth.assertThat
import maestro.conformance.behavior.OracleKind
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ReporterTest {
    @TempDir lateinit var tmp: File

    private fun rec(cmd: String, pass: Boolean) = CommandRecord(
        command = cmd, coverage = "framework-sensitive",
        args = mapOf("point" to listOf(10, 20)),
        oracleKind = OracleKind.APP_EVENT,
        expected = mapOf("event" to "TAP"), actual = mapOf("event" to "TAP"),
        verdict = pass, failureReason = if (pass) null else "no event", actMs = 12, totalMs = 30,
    )

    @Test fun `writes command json with verdict and fields`() {
        val r = Reporter(tmp)
        r.writeCommand("api34-native", rec("tap", true))
        val f = File(tmp, "cells/api34-native/tap/command.json")
        assertThat(f.exists()).isTrue()
        val text = f.readText()
        assertThat(text).contains("\"command\" : \"tap\"")
        assertThat(text).contains("\"verdict\" : \"PASS\"")
    }

    @Test fun `summary html lists cells and a fail count`() {
        val r = Reporter(tmp)
        r.writeCell("api34-native", listOf(rec("tap", true), rec("swipe", false)))
        r.writeSummary("device: emulator-5554")
        val index = File(tmp, "index.html").readText()
        assertThat(index).contains("api34-native")
        assertThat(File(tmp, "summary.json").readText()).contains("\"failed\" : 1")
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :maestro-client:conformanceTest --tests "maestro.conformance.report.ReporterTest"`
Expected: FAIL — types unresolved.

- [ ] **Step 3: Implement**

`CommandRecord.kt`:

```kotlin
package maestro.conformance.report

import maestro.conformance.behavior.OracleKind

data class CommandRecord(
    val command: String,
    val coverage: String,
    val args: Map<String, Any?>,
    val oracleKind: OracleKind,
    val expected: Map<String, Any?>,
    val actual: Map<String, Any?>,
    val verdict: Boolean,
    val failureReason: String?,
    val actMs: Long,
    val totalMs: Long,
)
```

`Reporter.kt`:

```kotlin
package maestro.conformance.report

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import java.io.File

class Reporter(private val root: File) {
    private val mapper = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
    private val cells = LinkedHashMap<String, List<CommandRecord>>()

    private fun verdictStr(p: Boolean) = if (p) "PASS" else "FAIL"

    fun writeCommand(cell: String, record: CommandRecord) {
        val dir = File(root, "cells/$cell/${record.command}").apply { mkdirs() }
        val json = mapper.writeValueAsString(
            linkedMapOf(
                "command" to record.command,
                "coverage" to record.coverage,
                "args" to record.args,
                "oracle" to linkedMapOf(
                    "kind" to record.oracleKind.name,
                    "expected" to record.expected,
                    "actual" to record.actual,
                ),
                "verdict" to verdictStr(record.verdict),
                "failureReason" to record.failureReason,
                "timings" to mapOf("actMs" to record.actMs, "totalMs" to record.totalMs),
            )
        )
        File(dir, "command.json").writeText(json)
    }

    fun writeCell(cell: String, records: List<CommandRecord>) {
        cells[cell] = records
        records.forEach { writeCommand(cell, it) }
        val dir = File(root, "cells/$cell").apply { mkdirs() }
        File(dir, "cell.json").writeText(
            mapper.writeValueAsString(records.map {
                mapOf("command" to it.command, "verdict" to verdictStr(it.verdict))
            })
        )
    }

    fun writeSummary(banner: String) {
        val all = cells.values.flatten()
        val failed = all.count { !it.verdict }
        val summary = linkedMapOf(
            "banner" to banner,
            "total" to all.size,
            "passed" to all.count { it.verdict },
            "failed" to failed,
            "cells" to cells.mapValues { (_, recs) ->
                recs.associate { it.command to verdictStr(it.verdict) }
            },
        )
        val json = mapper.writeValueAsString(summary)
        File(root, "summary.json").writeText(json)
        File(root, "summary.js").writeText("window.SUMMARY = $json;")
        File(root, "index.html").writeText(buildHtml(banner))
    }

    private fun buildHtml(banner: String): String {
        val rows = cells.entries.joinToString("\n") { (cell, recs) ->
            val tds = recs.joinToString("") { r ->
                val color = if (r.verdict) "#1b5e20" else "#b71c1c"
                "<td style='background:$color;color:#fff'>${r.command}</td>"
            }
            "<tr><th>$cell</th>$tds</tr>"
        }
        return """
            <!doctype html><html><head><meta charset="utf-8">
            <title>Driver Conformance</title></head><body>
            <h1>Driver Conformance</h1><p>$banner</p>
            <table border="1" cellspacing="0" cellpadding="6">$rows</table>
            <script src="./summary.js"></script></body></html>
        """.trimIndent()
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :maestro-client:conformanceTest --tests "maestro.conformance.report.ReporterTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add maestro-client/src/conformance
git commit -m "feat(conformance): Reporter writing command.json/cell.json/summary + HTML matrix"
```

---

## Task 7: `DeviceProvider` interface, `AttachedDeviceProvider`, and live logcat tailing

**Files:**
- Create: `maestro-client/src/conformance/kotlin/maestro/conformance/device/DeviceProvider.kt`
- Create: `maestro-client/src/conformance/kotlin/maestro/conformance/device/AttachedDeviceProvider.kt`
- Modify: `maestro-client/src/conformance/kotlin/maestro/conformance/logcat/LogcatEventReader.kt` (add `startTailing(serial)` / `close()`)

**Interfaces:**
- Produces:
  - `data class DeviceSpec(val apiLevel: Int)`
  - `class DeviceHandle(val serial: String, val driver: AndroidDriver, val apiLevel: Int, val userSupplied: Boolean)`
  - `interface DeviceProvider { fun acquire(spec: DeviceSpec): DeviceHandle; fun release(handle: DeviceHandle) }`
  - `AttachedDeviceProvider(serial: String)` — wraps a running serial; `acquire` builds `AndroidDriver(Dadb.create("localhost", adbPortFor(serial)) ... )`, prints the BYO banner; `release` is a no-op (don't tear down a user device).
  - `LogcatEventReader.startTailing(serial: String)` spawns `adb -s <serial> logcat -v threadtime -s MAESTRO_FIXTURE` and feeds `ingest`; `close()` stops it.

> This task is **device-dependent**; its "test" is a manual smoke against any running emulator/device, not a unit test. Document the manual check in the step.

- [ ] **Step 1: Implement the interface + handle**

`DeviceProvider.kt`:

```kotlin
package maestro.conformance.device

import maestro.drivers.AndroidDriver

data class DeviceSpec(val apiLevel: Int)

class DeviceHandle(
    val serial: String,
    val driver: AndroidDriver,
    val apiLevel: Int,
    val userSupplied: Boolean,
)

interface DeviceProvider {
    fun acquire(spec: DeviceSpec): DeviceHandle
    fun release(handle: DeviceHandle)
}
```

- [ ] **Step 2: Implement `AttachedDeviceProvider`**

`AttachedDeviceProvider.kt`:

```kotlin
package maestro.conformance.device

import dadb.Dadb
import maestro.drivers.AndroidDriver

/** BYO: run against an already-connected serial (e.g. emulator-5554). */
class AttachedDeviceProvider(private val serial: String) : DeviceProvider {
    override fun acquire(spec: DeviceSpec): DeviceHandle {
        println("⚠ user-supplied device $serial — state not managed by harness")
        val dadb = Dadb.list().find { it.toString() == serial }
            ?: error("Device $serial not found in `adb devices`")
        val driver = AndroidDriver(dadb, emulatorName = serial)
        driver.open()
        val api = Cmd.run("adb", "-s", serial, "shell", "getprop", "ro.build.version.sdk")
            .stdout.trim().toInt()
        return DeviceHandle(serial, driver, api, userSupplied = true)
    }

    override fun release(handle: DeviceHandle) {
        handle.driver.close() // do NOT wipe/kill a user-supplied device
    }
}
```

- [ ] **Step 3: Add live tailing to `LogcatEventReader`**

Append to `LogcatEventReader.kt`:

```kotlin
    private var tailProc: Process? = null
    private var tailThread: Thread? = null

    fun startTailing(serial: String) {
        // Clear backlog so we only see events from this run.
        Cmd.run("adb", "-s", serial, "logcat", "-c")
        val p = ProcessBuilder("adb", "-s", serial, "logcat", "-v", "threadtime", "-s", "MAESTRO_FIXTURE")
            .redirectErrorStream(true).start()
        tailProc = p
        tailThread = Thread { p.inputStream.bufferedReader().forEachLine { ingest(it) } }.apply {
            isDaemon = true; start()
        }
    }

    fun close() {
        tailProc?.destroyForcibly()
        tailThread?.join(2000)
    }
```

(Add `import maestro.conformance.device.Cmd` to the file.)

- [ ] **Step 4: Manual smoke check**

With any emulator running (`adb devices` shows e.g. `emulator-5554`):
Run a scratch main (or `kotlinc`-less: add a temporary `echo` test) that does
`LogcatEventReader().apply { startTailing("emulator-5554") }`, then in another shell:
`adb -s emulator-5554 shell log -t MAESTRO_FIXTURE '{"epoch":"x","seq":1,"event":"SELFTEST"}'`
Expected: `latestWatermark()` returns `Watermark("x", 1)`.
(Keep this as a throwaway check; remove before commit.)

- [ ] **Step 5: Verify compiles & commit**

Run: `./gradlew :maestro-client:compileConformanceKotlin`
Expected: BUILD SUCCESSFUL.

```bash
git add maestro-client/src/conformance
git commit -m "feat(conformance): DeviceProvider + AttachedDeviceProvider + live logcat tailing"
```

---

## Task 8: `FreshAvdProvider` + `Preflight`

**Files:**
- Create: `maestro-client/src/conformance/kotlin/maestro/conformance/device/FreshAvdProvider.kt`
- Create: `maestro-client/src/conformance/kotlin/maestro/conformance/device/Preflight.kt`

**Interfaces:**
- Consumes: `Cmd` (Task 3), `DeviceProvider`/`DeviceHandle` (Task 7).
- Produces:
  - `object Preflight { fun check() }` — verifies `adb`, `avdmanager`, `emulator`, `sdkmanager` on PATH; aborts with an actionable message otherwise.
  - `class FreshAvdProvider(private val abi: String = "x86_64")` implementing `DeviceProvider`:
    - `acquire`: `sdkmanager` install image → `avdmanager create avd -n maestro-conformance-api{N} -k "system-images;android-{N};google_apis;<abi>" --device pixel_6 --force` → boot `emulator @name -no-snapshot -no-window -no-audio -no-boot-anim -accel on -no-metrics -ports 5554,5555` → wait for `sys.boot_completed==1` + `cmd package list packages` → pin GBoard IME → `AndroidDriver(Dadb.create("localhost",5555)).open()`.
    - `release`: `adb -s emulator-5554 emu kill` → wait for port free → `adb kill-server` → delete the AVD.

> Device-dependent; verification is an integration smoke (Task 12 exercises it for real). Here, write the provider and a `Preflight` unit-ish test that only checks the abort message wiring with a fake PATH.

- [ ] **Step 1: Implement `Preflight`**

`Preflight.kt`:

```kotlin
package maestro.conformance.device

object Preflight {
    private val REQUIRED = listOf("adb", "avdmanager", "emulator", "sdkmanager")

    fun check() {
        val missing = REQUIRED.filter { tool ->
            Cmd.run("/bin/sh", "-c", "command -v $tool").exit != 0
        }
        require(missing.isEmpty()) {
            "Missing Android SDK tools on PATH: ${missing.joinToString()}. " +
                "Install cmdline-tools + platform-tools and ensure they're on PATH."
        }
    }
}
```

- [ ] **Step 2: Implement `FreshAvdProvider`**

`FreshAvdProvider.kt`:

```kotlin
package maestro.conformance.device

import dadb.Dadb
import maestro.drivers.AndroidDriver

class FreshAvdProvider(private val abi: String = "x86_64") : DeviceProvider {
    private val consolePort = 5554
    private val adbPort = 5555
    private val serial = "emulator-$consolePort"
    private var emulator: Process? = null

    override fun acquire(spec: DeviceSpec): DeviceHandle {
        Preflight.check()
        val image = "system-images;android-${spec.apiLevel};google_apis;$abi"
        val name = "maestro-conformance-api${spec.apiLevel}"

        require(Cmd.run("/bin/sh", "-c", "yes | sdkmanager \"$image\"", timeoutMs = 600_000).ok) {
            "Failed to install system image $image"
        }
        require(Cmd.run("/bin/sh", "-c",
            "echo no | avdmanager create avd -n $name -k \"$image\" --device pixel_6 --force").ok) {
            "Failed to create AVD $name"
        }

        emulator = ProcessBuilder(
            "emulator", "@$name",
            "-no-snapshot", "-no-window", "-no-audio", "-no-boot-anim",
            "-accel", "on", "-no-metrics", "-ports", "$consolePort,$adbPort",
        ).redirectErrorStream(true).start()

        waitForBoot()
        pinGboardIme()

        val dadb = Dadb.create("localhost", adbPort)
        val driver = AndroidDriver(dadb, emulatorName = serial)
        driver.open()
        return DeviceHandle(serial, driver, spec.apiLevel, userSupplied = false)
    }

    private fun waitForBoot(timeoutMs: Long = 180_000) {
        Cmd.run("adb", "-s", serial, "wait-for-device", timeoutMs = timeoutMs)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val booted = Cmd.run("adb", "-s", serial, "shell", "getprop", "sys.boot_completed").stdout.trim() == "1"
            val pkg = Cmd.run("adb", "-s", serial, "shell", "cmd", "package", "list", "packages").ok
            if (booted && pkg) return
            Thread.sleep(1000)
        }
        error("Emulator $serial did not boot within ${timeoutMs}ms")
    }

    /** Keyboard commands in AndroidDriver match the GBoard package; pin it so they don't false-fail. */
    private fun pinGboardIme() {
        val gboard = "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME"
        val imes = Cmd.run("adb", "-s", serial, "shell", "ime", "list", "-s").stdout
        if (imes.contains("com.google.android.inputmethod.latin")) {
            Cmd.run("adb", "-s", serial, "shell", "ime", "enable", gboard)
            Cmd.run("adb", "-s", serial, "shell", "ime", "set", gboard)
        } else {
            println("⚠ GBoard IME not present on this image — keyboard commands may be skipped/red. " +
                "Prefer a google_apis_playstore image.")
        }
    }

    override fun release(handle: DeviceHandle) {
        runCatching { handle.driver.close() }
        runCatching { Cmd.run("adb", "-s", serial, "emu", "kill") }
        emulator?.destroyForcibly()
        // wait for the adb port to free, then reset the daemon (maestro-device's loop-pressure fix)
        repeat(10) {
            if (Cmd.run("/bin/sh", "-c", "lsof -nP -iTCP:$adbPort -sTCP:LISTEN").exit != 0) return@repeat
            Thread.sleep(1000)
        }
        Cmd.run("adb", "kill-server")
        Cmd.run("/bin/sh", "-c", "avdmanager delete avd -n maestro-conformance-api${handle.apiLevel}")
    }
}
```

- [ ] **Step 3: Verify compiles**

Run: `./gradlew :maestro-client:compileConformanceKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add maestro-client/src/conformance
git commit -m "feat(conformance): FreshAvdProvider (stock SDK, single port, GBoard pin, cleanup) + Preflight"
```

---

## Task 9: Native fixture module — emitter + activity + SELFTEST/LAUNCHED

> **Confirm with the user first:** this creates a new Android module `fixtures/native/`. Per Global Constraints, fixtures are necessarily Android apps; this is distinct from the "no new harness module" rule.

**Files:**
- Create: `fixtures/native/build.gradle.kts`
- Modify: `settings.gradle.kts` (include the module)
- Create: `fixtures/native/src/main/AndroidManifest.xml`
- Create: `fixtures/native/src/main/java/dev/mobile/maestro/fixture/FixtureEmitter.kt`
- Create: `fixtures/native/src/main/java/dev/mobile/maestro/fixture/FixtureActivity.kt`

**Interfaces:**
- Produces: an installable APK with appId `dev.mobile.maestro.fixture`, launchable with `am start ... -e route <Screen>` (and extras echoed in `LAUNCHED.args`). Emitter writes `Log.d("MAESTRO_FIXTURE", json)` with a per-process `epoch` and atomic incrementing `seq`.

- [ ] **Step 1: Create the Gradle module** (mirror `maestro-android/build.gradle.kts`)

`fixtures/native/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.mobile.maestro.fixture"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.mobile.maestro.fixture"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes { getByName("debug") { isMinifyEnabled = false } }
    kotlinOptions { jvmTarget = "1.8" }
}

// No extra deps: the fixture uses only platform APIs (android.app.Activity,
// android.util.Log) + org.json, all in the Android SDK.
```

Add to `settings.gradle.kts`:

```kotlin
include(":fixtures:native")
```

- [ ] **Step 2: Manifest** with a launcher activity + custom deep-link scheme

`fixtures/native/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:label="Maestro Fixture" android:theme="@android:style/Theme.Material.Light">
        <activity android:name=".FixtureActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="maestrofixture" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 3: Emitter**

`FixtureEmitter.kt`:

```kotlin
package dev.mobile.maestro.fixture

import android.util.Log
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

object FixtureEmitter {
    private const val TAG = "MAESTRO_FIXTURE"
    // Fresh per process start; resets implicitly on cold start / pm clear (seq resets too).
    private val epoch: String = java.lang.Long.toHexString(System.nanoTime())
    private val seq = AtomicInteger(0)

    fun emit(type: String, payload: Map<String, Any?> = emptyMap()) {
        val o = JSONObject()
        o.put("epoch", epoch)
        o.put("seq", seq.incrementAndGet())
        o.put("event", type)
        for ((k, v) in payload) o.put(k, v)
        Log.d(TAG, o.toString())
    }

    fun currentEpoch() = epoch
}
```

- [ ] **Step 4: Activity** — emits SELFTEST + LAUNCHED(args), handles MARK + routing

`FixtureActivity.kt`:

```kotlin
package dev.mobile.maestro.fixture

import android.app.Activity
import android.os.Bundle
import android.widget.FrameLayout

class FixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(FrameLayout(this).apply { id = android.R.id.content })

        FixtureEmitter.emit("SELFTEST")

        val args = HashMap<String, Any?>()
        intent.extras?.keySet()?.forEach { k -> args[k] = intent.extras?.get(k)?.toString() }
        // Deep-link data (openLink) is echoed too.
        intent.dataString?.let { args["data"] = it }
        FixtureEmitter.emit("LIFECYCLE", mapOf("state" to "LAUNCHED", "args" to args))

        val route = intent.getStringExtra("route") ?: "TapScreen"
        Router.show(this, route)
    }
}
```

(Placeholder `Router` is defined in Task 10 with the screens.)

- [ ] **Step 5: Build the APK**

Run: `./gradlew :fixtures:native:assembleDebug`
Expected: BUILD SUCCESSFUL; APK at `fixtures/native/build/outputs/apk/debug/native-debug.apk`.

- [ ] **Step 6: Commit**

```bash
git add fixtures settings.gradle.kts
git commit -m "feat(fixture-native): module + emitter (epoch/seq via Log.d) + activity (SELFTEST/LAUNCHED/route)"
```

---

## Task 10: Native fixture — `TapScreen` + `Router` + MARK barrier

**Files:**
- Create: `fixtures/native/src/main/java/dev/mobile/maestro/fixture/Router.kt`
- Create: `fixtures/native/src/main/java/dev/mobile/maestro/fixture/screens/TapScreen.kt`
- Create: `fixtures/native/src/main/java/dev/mobile/maestro/fixture/MarkReceiver.kt`

**Interfaces:**
- Produces:
  - `TapScreen`: a full-screen view with a top-level touch listener emitting `TOUCH {x,y}` (raw device px) and a `tap_target` view (id `tap_target` via `contentDescription`) emitting `TAP {target,x,y}`; plus a `longpress_target` emitting `LONG_PRESS {target, downMs}`.
  - MARK barrier: a broadcast receiver `dev.mobile.maestro.fixture.MARK` that calls `FixtureEmitter.emit("MARK")` so the harness can capture a watermark deterministically.

- [ ] **Step 1: Router**

`Router.kt`:

```kotlin
package dev.mobile.maestro.fixture

import android.app.Activity
import dev.mobile.maestro.fixture.screens.TapScreen

object Router {
    fun show(activity: Activity, route: String) {
        when (route) {
            "TapScreen" -> TapScreen.install(activity)
            else -> TapScreen.install(activity) // other screens added in later tasks
        }
    }
}
```

- [ ] **Step 2: TapScreen** — raw TOUCH + TAP + LONG_PRESS

`screens/TapScreen.kt`:

```kotlin
package dev.mobile.maestro.fixture.screens

import android.app.Activity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import dev.mobile.maestro.fixture.FixtureEmitter

object TapScreen {
    fun install(activity: Activity) {
        val root = FrameLayout(activity)

        // Top-level raw-coordinate reporter (independent of which widget handles the hit).
        root.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) {
                FixtureEmitter.emit("TOUCH", mapOf("x" to e.rawX.toInt(), "y" to e.rawY.toInt()))
            }
            false
        }

        val tap = Button(activity).apply {
            text = "tap"
            contentDescription = "tap_target"
            setOnClickListener {
                FixtureEmitter.emit("TAP", mapOf("target" to "tap_target"))
            }
        }
        val longPress = Button(activity).apply {
            text = "longpress"
            contentDescription = "longpress_target"
            var downAt = 0L
            setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { downAt = System.currentTimeMillis(); false }
                    MotionEvent.ACTION_UP -> {
                        val downMs = System.currentTimeMillis() - downAt
                        if (downMs >= 500) FixtureEmitter.emit(
                            "LONG_PRESS", mapOf("target" to "longpress_target", "downMs" to downMs)
                        )
                        false
                    }
                    else -> false
                }
            }
        }

        root.addView(tap, FrameLayout.LayoutParams(600, 200).apply { topMargin = 400; leftMargin = 100 })
        root.addView(longPress, FrameLayout.LayoutParams(600, 200).apply { topMargin = 800; leftMargin = 100 })
        activity.setContentView(root)
    }
}
```

- [ ] **Step 3: MARK receiver** — register in the activity

`MarkReceiver.kt`:

```kotlin
package dev.mobile.maestro.fixture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class MarkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        FixtureEmitter.emit("MARK")
    }
}
```

Register it in `FixtureActivity.onCreate` (after the LAUNCHED emit):

```kotlin
        registerReceiver(MarkReceiver(), android.content.IntentFilter("dev.mobile.maestro.fixture.MARK"),
            // API 33+ requires an export flag
            if (android.os.Build.VERSION.SDK_INT >= 33) Context.RECEIVER_EXPORTED else 0)
```

(Add the needed imports: `android.content.Context`.)

- [ ] **Step 4: Build & install smoke**

Run: `./gradlew :fixtures:native:assembleDebug`
Then with an emulator running:
`adb install -r fixtures/native/build/outputs/apk/debug/native-debug.apk`
`adb shell am start -n dev.mobile.maestro.fixture/.FixtureActivity -e route TapScreen`
`adb logcat -s MAESTRO_FIXTURE` → see `SELFTEST` then `LIFECYCLE LAUNCHED`. Tap the screen → `TOUCH`.
`adb shell am broadcast -a dev.mobile.maestro.fixture.MARK` → see `MARK`.

- [ ] **Step 5: Commit**

```bash
git add fixtures
git commit -m "feat(fixture-native): TapScreen (TOUCH/TAP/LONG_PRESS), Router, MARK barrier receiver"
```

---

## Task 11: Wire `markWatermark` + bundle the fixture APK; `TapBehavior`

**Files:**
- Modify: `fixtures/native/build.gradle.kts` (copy APK into harness resources)
- Create: `maestro-client/src/conformance/kotlin/maestro/conformance/behavior/commands/TapBehavior.kt`
- Create: `maestro-client/src/conformance/kotlin/maestro/conformance/fixture/FixtureApp.kt`

**Interfaces:**
- Consumes: `BehaviorContext` (Task 5), `LogcatEventReader`, `AndroidDriver.tap` / `deviceInfo`.
- Produces:
  - `data class FixtureApp(val framework: String, val appId: String, val apkResource: String)` and `object FixtureCatalog { val native = FixtureApp("native", "dev.mobile.maestro.fixture", "/native-fixture.apk") }`.
  - `markWatermark` implementation (in the runner, Task 12): broadcast `am broadcast -a dev.mobile.maestro.fixture.MARK`, then poll `reader.latestWatermark()` until it advances; return it.
  - `class TapBehavior : CommandBehavior` — arrange routes to TapScreen, baseline = markWatermark, act = `driver.tap(center)`, verify = a `TAP` event with `target=="tap_target"` past the watermark; native also checks a `TOUCH` near the commanded point.

- [ ] **Step 1: Copy the fixture APK into the harness resources** (mirror maestro-android pattern)

Append to `fixtures/native/build.gradle.kts`:

```kotlin
val copyNativeFixture by tasks.registering(Copy::class) {
    dependsOn("assembleDebug")
    from("build/outputs/apk/debug/native-debug.apk")
    into("${rootDir}/maestro-client/src/conformance/resources")
    rename { "native-fixture.apk" }
}
tasks.named("assemble") { finalizedBy(copyNativeFixture) }
```

- [ ] **Step 2: FixtureApp catalog**

`fixture/FixtureApp.kt`:

```kotlin
package maestro.conformance.fixture

data class FixtureApp(val framework: String, val appId: String, val apkResource: String)

object FixtureCatalog {
    val native = FixtureApp("native", "dev.mobile.maestro.fixture", "/native-fixture.apk")
    fun byName(name: String): FixtureApp = when (name) {
        "native" -> native
        else -> error("Unknown framework: $name (Phase 1 ships only 'native')")
    }
}
```

- [ ] **Step 3: TapBehavior**

`behavior/commands/TapBehavior.kt`:

```kotlin
package maestro.conformance.behavior.commands

import maestro.Point
import maestro.conformance.behavior.*

class TapBehavior : CommandBehavior {
    override val name = "tap"
    override val coverage = Coverage.FRAMEWORK_SENSITIVE

    override fun run(ctx: BehaviorContext): CommandOutcome {
        // arrange: relaunch fixture on TapScreen via deep link (done by the runner before run()).
        // resolve target center from the on-device tree.
        val node = ctx.driver.contentDescriptor()
        val bounds = TreeBounds.find(node, "tap_target")
            ?: return fail(ctx, "tap_target not found in hierarchy")
        val point = Point(bounds.centerX, bounds.centerY)

        val w = ctx.markWatermark()          // baseline
        ctx.driver.tap(point)                // act

        val taps = pollFor(ctx, w, "TAP")
        val tap = taps.firstOrNull { it.payload["target"] == "tap_target" }
        val expected = mapOf("event" to "TAP", "target" to "tap_target")
        return if (tap != null) {
            CommandOutcome(Verdict.pass(), OracleKind.APP_EVENT, expected,
                tap.payload, mapOf("point" to listOf(point.x, point.y)))
        } else {
            CommandOutcome(Verdict.fail("no TAP on tap_target past watermark"),
                OracleKind.APP_EVENT, expected,
                mapOf("taps" to taps.map { it.payload }), mapOf("point" to listOf(point.x, point.y)))
        }
    }

    private fun pollFor(ctx: BehaviorContext, w: maestro.conformance.logcat.Watermark, type: String,
                        timeoutMs: Long = 3000): List<maestro.conformance.logcat.FixtureEvent> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val ev = ctx.reader.eventsAfter(w, type)
            if (ev.isNotEmpty()) return ev
            Thread.sleep(100)
        }
        return ctx.reader.eventsAfter(w, type)
    }

    private fun fail(ctx: BehaviorContext, reason: String) = CommandOutcome(
        Verdict.fail(reason), OracleKind.APP_EVENT, emptyMap(), emptyMap(), emptyMap())
}
```

- [ ] **Step 4: `TreeBounds` helper** (parse element bounds from `TreeNode`)

Create `behavior/commands/TreeBounds.kt`:

```kotlin
package maestro.conformance.behavior.commands

import maestro.TreeNode

data class Bounds(val l: Int, val t: Int, val r: Int, val b: Int) {
    val centerX get() = (l + r) / 2
    val centerY get() = (t + b) / 2
}

object TreeBounds {
    /** Find a node whose resource-id/accessibility-text matches [id], return its pixel bounds. */
    fun find(node: TreeNode, id: String): Bounds? {
        val attrs = node.attributes
        val matches = attrs["resource-id"]?.endsWith(id) == true ||
            attrs["text"] == id || attrs["accessibilityText"] == id ||
            attrs["content-desc"] == id || attrs["hintText"] == id
        if (matches) parseBounds(attrs["bounds"])?.let { return it }
        for (child in node.children) find(child, id)?.let { return it }
        return null
    }

    // Android dumps bounds as "[l,t][r,b]".
    private fun parseBounds(s: String?): Bounds? {
        if (s == null) return null
        val m = Regex("""\[(\d+),(\d+)]\[(\d+),(\d+)]""").find(s) ?: return null
        val (l, t, r, b) = m.destructured
        return Bounds(l.toInt(), t.toInt(), r.toInt(), b.toInt())
    }
}
```

> **Implementer note:** confirm the exact attribute key AndroidDriver uses for the accessibility id by inspecting a real `contentDescriptor()` dump in Task 12; adjust `find`'s match keys to whatever the dump actually contains (likely `resource-id` and/or `content-desc`). This is the one place where a wrong key silently breaks resolution.

- [ ] **Step 5: Verify compiles & commit**

Run: `./gradlew :maestro-client:compileConformanceKotlin`
Expected: BUILD SUCCESSFUL.

```bash
git add maestro-client/src/conformance fixtures/native/build.gradle.kts
git commit -m "feat(conformance): FixtureCatalog, TapBehavior + TreeBounds, bundle native APK"
```

---

## Task 12: Runner wiring — first end-to-end green (`tap` on one API)

**Files:**
- Create: `maestro-client/src/conformance/kotlin/maestro/conformance/runner/ConformanceRunner.kt`
- Modify: `maestro-client/src/conformance/kotlin/maestro/conformance/cli/ConformanceCli.kt` (call the runner)
- Create (helper): the fixture install + route + markWatermark live here.

**Interfaces:**
- Consumes: everything from Tasks 1–11.
- Produces: `class ConformanceRunner(provider, reporter, behaviors)` with `fun run(apis: List<Int>, frameworks: List<String>, commands: List<String>?)`. Loops **per API → per framework → per command**, single device. For each framework it installs the fixture APK, then for each command it: relaunches the fixture on the command's screen (deep link), builds a `BehaviorContext` (with a real `markWatermark`), runs the behavior, records the outcome.

- [ ] **Step 1: Implement the runner**

`runner/ConformanceRunner.kt`:

```kotlin
package maestro.conformance.runner

import maestro.conformance.behavior.*
import maestro.conformance.device.*
import maestro.conformance.fixture.FixtureCatalog
import maestro.conformance.logcat.LogcatEventReader
import maestro.conformance.logcat.Watermark
import maestro.conformance.report.CommandRecord
import maestro.conformance.report.Reporter

class ConformanceRunner(
    private val provider: DeviceProvider,
    private val reporter: Reporter,
    private val behaviors: List<CommandBehavior>,
) {
    fun run(apis: List<Int>, frameworks: List<String>, commands: List<String>?) {
        val selected = behaviors.filter { commands == null || it.name in commands }
        var banner = ""
        for (api in apis) {
            val handle = provider.acquire(DeviceSpec(api))
            banner = "device: ${handle.serial} (api $api)" +
                if (handle.userSupplied) " [user-supplied]" else ""
            val reader = LogcatEventReader().apply { startTailing(handle.serial) }
            try {
                for (fw in frameworks) {
                    val fixture = FixtureCatalog.byName(fw)
                    installFixture(handle.serial, fixture.apkResource)
                    val cell = "api$api-$fw"
                    val records = ArrayList<CommandRecord>()
                    for (b in selected) {
                        records += runCommand(handle, reader, fixture.appId, b, cell)
                    }
                    reporter.writeCell(cell, records)
                    uninstall(handle.serial, fixture.appId)
                }
            } finally {
                reader.close()
                provider.release(handle)
            }
        }
        reporter.writeSummary(banner)
    }

    private fun runCommand(
        handle: DeviceHandle, reader: LogcatEventReader, appId: String,
        behavior: CommandBehavior, cell: String,
    ): CommandRecord {
        // arrange: relaunch on the command's screen (deep link, not a tap).
        val screen = ScreenFor.of(behavior.name)
        handle.driver.launchApp(appId, mapOf("route" to screen))
        Thread.sleep(800) // let the screen settle + SELFTEST/LAUNCHED flush

        val ctx = BehaviorContext(
            driver = handle.driver, reader = reader, serial = handle.serial,
            apiLevel = handle.apiLevel, appId = appId,
            markWatermark = { markWatermark(handle.serial, reader) },
        )
        val start = System.currentTimeMillis()
        val outcome = runCatching { behavior.run(ctx) }.getOrElse {
            CommandOutcome(Verdict.fail("behavior threw: ${it.message}"),
                OracleKind.APP_EVENT, emptyMap(), emptyMap(), emptyMap())
        }
        val total = System.currentTimeMillis() - start
        return CommandRecord(
            command = behavior.name, coverage = behavior.coverage.name.lowercase().replace('_', '-'),
            args = outcome.args, oracleKind = outcome.oracleKind,
            expected = outcome.expected, actual = outcome.actual,
            verdict = outcome.verdict.pass, failureReason = outcome.verdict.reason,
            actMs = total, totalMs = total,
        )
    }

    private fun markWatermark(serial: String, reader: LogcatEventReader): Watermark {
        val before = reader.latestWatermark()
        Cmd.run("adb", "-s", serial, "shell", "am", "broadcast",
            "-a", "dev.mobile.maestro.fixture.MARK")
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline) {
            val now = reader.latestWatermark()
            if (now != null && now != before) return now
            Thread.sleep(50)
        }
        return reader.latestWatermark() ?: error("No MARK observed; fixture not emitting")
    }

    private fun installFixture(serial: String, apkResource: String) {
        val apk = kotlin.io.path.createTempFile("fixture", ".apk").toFile()
        ConformanceRunner::class.java.getResourceAsStream(apkResource)!!.use { it.copyTo(apk.outputStream()) }
        require(Cmd.run("adb", "-s", serial, "install", "-r", apk.absolutePath).ok) { "install failed" }
    }

    private fun uninstall(serial: String, appId: String) {
        Cmd.run("adb", "-s", serial, "uninstall", appId)
    }
}

object ScreenFor {
    fun of(command: String): String = when (command) {
        "tap", "longPress" -> "TapScreen"
        else -> "TapScreen" // extended in later tasks
    }
}
```

- [ ] **Step 2: Wire the CLI to the runner**

Replace `ConformanceCli.run()`:

```kotlin
    override fun run() {
        val apis = maestro.conformance.cli.Selection.parseApis(api)
        val frameworks = maestro.conformance.cli.Selection.parseList(framework)
        val commands = command?.let { maestro.conformance.cli.Selection.parseList(it) }
        val provider = device?.let { maestro.conformance.device.AttachedDeviceProvider(it) }
            ?: maestro.conformance.device.FreshAvdProvider()
        val reporter = maestro.conformance.report.Reporter(java.io.File(out))
        val behaviors = listOf(maestro.conformance.behavior.commands.TapBehavior())
        maestro.conformance.runner.ConformanceRunner(provider, reporter, behaviors)
            .run(apis, frameworks, commands)
        echo("Report: ${java.io.File(out, "index.html").absolutePath}")
    }
```

- [ ] **Step 3: End-to-end run on one API (against a running emulator first for speed)**

Build the fixture + bundle: `./gradlew :fixtures:native:assemble`
With an emulator running, BYO mode (fast iteration):
Run: `./gradlew :maestro-client:driverConformance --args="--api 34 --framework native --command tap --device emulator-5554 --out ./report"`
Expected: console shows the BYO banner; finishes; `report/cells/api34-native/tap/command.json` has `"verdict" : "PASS"`; `report/index.html` shows a green `tap` cell.
*(If `tap_target` isn't found, inspect a real `contentDescriptor()` dump and fix `TreeBounds.find` match keys per Task 11 Step 4.)*

- [ ] **Step 4: End-to-end run on one API with provisioning (the real path)**

Run: `./gradlew :maestro-client:driverConformance --args="--api 34 --framework native --command tap --out ./report"`
Expected: a fresh AVD is created, booted, tap passes, AVD torn down; report green.

- [ ] **Step 5: Commit**

```bash
git add maestro-client/src/conformance
git commit -m "feat(conformance): runner wiring — tap proven end-to-end on one API (APP oracle)"
```

---

## Task 13: Add a RET-oracle command early — `takeScreenshot`

**Files:**
- Create: `maestro-client/src/conformance/kotlin/maestro/conformance/behavior/commands/TakeScreenshotBehavior.kt`
- Modify: `ConformanceCli.kt` (register it), `ScreenFor` (maps to TapScreen)

**Interfaces:**
- Consumes: `AndroidDriver.takeScreenshot(out: Sink, compressed: Boolean)`, `okio.Buffer`.
- Produces: `class TakeScreenshotBehavior : CommandBehavior` (coverage `DEVICE_LEVEL`) — RET oracle: capture bytes, assert decode + non-zero dims + not uniformly blank (per §B6).

- [ ] **Step 1: Implement**

`TakeScreenshotBehavior.kt`:

```kotlin
package maestro.conformance.behavior.commands

import maestro.conformance.behavior.*
import okio.Buffer
import javax.imageio.ImageIO

class TakeScreenshotBehavior : CommandBehavior {
    override val name = "takeScreenshot"
    override val coverage = Coverage.DEVICE_LEVEL

    override fun run(ctx: BehaviorContext): CommandOutcome {
        val buf = Buffer()
        ctx.driver.takeScreenshot(buf, compressed = false)   // act
        val bytes = buf.readByteArray()
        val img = runCatching { ImageIO.read(bytes.inputStream()) }.getOrNull()
        val expected = mapOf("decodes" to true, "nonZeroDims" to true, "notBlank" to true)
        if (img == null || img.width == 0 || img.height == 0) {
            return CommandOutcome(Verdict.fail("screenshot did not decode / zero dims"),
                OracleKind.RETURN_VALUE, expected, mapOf("bytes" to bytes.size), emptyMap())
        }
        // "not uniformly blank": sample a few pixels, require at least two distinct colors.
        val colors = listOf(0 to 0, img.width / 2 to img.height / 2, img.width - 1 to img.height - 1)
            .map { (x, y) -> img.getRGB(x.coerceIn(0, img.width - 1), y.coerceIn(0, img.height - 1)) }
            .toSet()
        val notBlank = colors.size >= 2 || bytes.size > 50_000
        val actual = mapOf("width" to img.width, "height" to img.height, "distinctSamples" to colors.size)
        return CommandOutcome(
            if (notBlank) Verdict.pass() else Verdict.fail("screenshot looks uniformly blank"),
            OracleKind.RETURN_VALUE, expected, actual, emptyMap())
    }
}
```

- [ ] **Step 2: Register + run**

Add `TakeScreenshotBehavior()` to the CLI's `behaviors` list. Run:
`./gradlew :maestro-client:driverConformance --args="--api 34 --framework native --command tap,takeScreenshot --device emulator-5554 --out ./report"`
Expected: both green; `report/cells/api34-native/takeScreenshot/command.json` has `oracle.kind == RETURN_VALUE`.

- [ ] **Step 3: Commit**

```bash
git add maestro-client/src/conformance
git commit -m "feat(conformance): takeScreenshot RET-oracle behavior (proves RET path)"
```

---

## Task 14: Remaining native screens (Swipe/Scroll/Input/Keyboard/Tree/Orientation/Animation/Lifecycle)

**Files:**
- Create one screen file per `fixtures/native/.../screens/` entry from §5.1, plus wire each into `Router`.

**Interfaces:**
- Produces the §5.1 screens, each emitting the events its commands assert. Build the screens in this single task (they're sibling UI views with no cross-dependencies); each is small.

Implement each screen to emit exactly the events the catalogue (§4.2/§4.3) expects. Per-screen requirements (event `type` + payload keys):

| Screen | Emits | Payload keys |
|---|---|---|
| `SwipeScreen` (`swipe_surface`, a scrollable) | `TOUCH`, `SWIPE` | `dir, dx, dy, durationMs, target` (measure down→up dir/distance/time on a top-level listener) |
| `ScrollScreen` (`scroll_container`, a `ScrollView`/`RecyclerView`) | `SCROLL` | `axis="Y", fromOffset, toOffset` (emit on scroll-change) |
| `InputScreen` (`text_field`, an `EditText`) | `TEXT_CHANGED` | `text` (emit on `TextWatcher.afterTextChanged`) |
| `KeyboardScreen` (`text_field`, no back-handler) | `IME`, `KEY` | `state=SHOWN/HIDDEN` (WindowInsets listener); `code` (`OnKeyListener`) |
| `TreeScreen` | (none) | static views with ids `tree_root`,`tree_label_a`,`tree_button_b` (RET oracle reads the tree) |
| `OrientationScreen` | `ORIENTATION` | `value=PORTRAIT/LANDSCAPE` (emit in `onConfigurationChanged`) |
| `AnimationScreen` (`animate_button`) | `ANIM` | `state=RUNNING/SETTLED` (start a 1.5s ValueAnimator on entry, emit SETTLED on end) |
| `AppLifecycleScreen` (`state_seed_button`) | `BACK`, `STATE`, `DEEPLINK` | `BACK` from `onBackPressed`; `STATE {seeded}` reads SharedPreferences; `DEEPLINK {data}` from intent |

- [ ] **Step 1:** Implement each screen following the `TapScreen` pattern (top-level listener where raw coords matter; `contentDescription = "<id>"` on each element; `FixtureEmitter.emit(...)` with the keys above). Extend `Router.show` with a branch per route.
- [ ] **Step 2:** Build: `./gradlew :fixtures:native:assemble`. Manually `am start ... -e route <Screen>` each and confirm the events appear in `adb logcat -s MAESTRO_FIXTURE`.
- [ ] **Step 3: Commit**

```bash
git add fixtures
git commit -m "feat(fixture-native): remaining §5.1 screens emitting their events"
```

---

## Task 15: Remaining Tier-A behaviors (one file each), registered

**Files:**
- Create one `behavior/commands/<Cmd>Behavior.kt` per remaining Tier-A command; register all in the CLI.
- Modify `ScreenFor.of` to map each command → its screen (per §5.1).

**Interfaces:**
- Each behavior follows the `TapBehavior` (APP) or `TakeScreenshotBehavior` (RET/PROBE) pattern. Implement per the §4.3 "Attributes verified" column. Coverage per §5.2.

Per-command implementation spec (driver call ← §4.2; assert ← §4.3; oracle/coverage ← §4.1/§5.2):

| Behavior | Oracle | Driver call | Assert (past watermark unless RET) | Coverage |
|---|---|---|---|---|
| `LongPressBehavior` | APP | `longPress(center("longpress_target"))` | `LONG_PRESS{target}`, `downMs ≈ 3000` (±800) | FRAMEWORK_SENSITIVE |
| `SwipeStartEndBehavior` | APP | `swipe(Point a,Point b,300)` | `SWIPE{dir=UP, sign(dy)<0, |dy| in band, durationMs≈300}` | FRAMEWORK_SENSITIVE |
| `SwipeDirectionBehavior` | APP | `swipe(SwipeDirection.LEFT,300)` | `SWIPE{dir=LEFT, sign(dx)<0, durationMs≈300}` | FRAMEWORK_SENSITIVE |
| `SwipeElementBehavior` | APP | `swipe(center,SwipeDirection.UP,300)` | `SWIPE{dir=UP, target=swipe_surface, durationMs≈300}` | FRAMEWORK_SENSITIVE |
| `InputTextBehavior` | APP | focus field; `inputText("Maestro 42!")` | `TEXT_CHANGED{text=="Maestro 42!"}` (ASCII-only if `!driver.isUnicodeInputSupported()`) | FRAMEWORK_SENSITIVE |
| `EraseTextBehavior` | APP | seed "ABCDE"; `eraseText(2)` | `TEXT_CHANGED{text=="ABC"}` | FRAMEWORK_SENSITIVE |
| `PressKeyBehavior` | APP | `pressKey(KeyCode.ENTER)` | `KEY{code=="ENTER"}` | MIXED |
| `BackPressBehavior` | APP | route to pushed sub-screen; `backPress()` | `BACK` + nav pop | MIXED |
| `ScrollVerticalBehavior` | APP | `scrollVertical()` | `SCROLL{axis=Y, toOffset>fromOffset}` | FRAMEWORK_SENSITIVE |
| `ContentDescriptorBehavior` | RET | `contentDescriptor(false)` | tree contains `tree_root`,`tree_label_a`,`tree_button_b`; bounds non-empty | FRAMEWORK_SENSITIVE |
| `QueryOnDeviceElementsBehavior` | RET | `queryOnDeviceElements(q for tree_label_a)` | non-empty; id matches | FRAMEWORK_SENSITIVE |
| `IsKeyboardVisibleBehavior` | RET+APP | focus field; `isKeyboardVisible()` | returns `true`; x-check `IME SHOWN` | MIXED |
| `HideKeyboardBehavior` | APP+probe | open IME; `hideKeyboard()` | `IME{state=HIDDEN}` + `isKeyboardVisible()==false` | DEVICE_LEVEL |
| `LaunchAppBehavior` | APP | `launchApp(appId, {"k":"v"})` | `LIFECYCLE{state=LAUNCHED, args=={k:v}}`, fresh epoch, seq==1 | DEVICE_LEVEL |
| `StopAppBehavior` | PROBE | `stopApp(appId)` | `pidof` empty after | DEVICE_LEVEL |
| `KillAppBehavior` | PROBE | `killApp(appId)` | `pidof` empty after | DEVICE_LEVEL |
| `ClearAppStateBehavior` | APP | seed→`stopApp`→`clearAppState`→relaunch | post-relaunch `STATE{seeded=false}` (new epoch) | DEVICE_LEVEL |
| `SetOrientationBehavior` | APP | `setOrientation(LANDSCAPE_LEFT)` | `ORIENTATION{value=LANDSCAPE}`; round-trip PORTRAIT | DEVICE_LEVEL |
| `OpenLinkBehavior` | APP | `openLink("maestrofixture://deeplink/ok",appId,false,false)` | `DEEPLINK{data=="...ok"}` | MIXED |
| `WaitUntilScreenIsStaticBehavior` | RET+APP | start anim; `waitUntilScreenIsStatic(5000)` | returns `true`; `ANIM SETTLED` before return | FRAMEWORK_SENSITIVE |
| `WaitForAppToSettleBehavior` | RET | `waitForAppToSettle(null,appId,5000)` | non-null; two `contentDescriptor()` equal after | FRAMEWORK_SENSITIVE |

Helper to add for PROBE commands (`pidof`): in a shared `Probe.kt`:

```kotlin
package maestro.conformance.behavior.commands
import maestro.conformance.device.Cmd
object Probe {
    fun pidOf(serial: String, appId: String): String =
        Cmd.run("adb", "-s", serial, "shell", "pidof", appId).stdout.trim()
}
```

- [ ] **Step 1:** Implement each behavior above (one file each), reusing `TapBehavior`'s `pollFor`/`TreeBounds` (extract `pollFor` into a shared `behavior/commands/Poll.kt` to keep DRY). Register all in the CLI `behaviors` list. Extend `ScreenFor.of` per §5.1.
- [ ] **Step 2:** Run the full Tier-A set on one API in BYO mode:
  `./gradlew :maestro-client:driverConformance --args="--api 34 --framework native --device emulator-5554 --out ./report"`
  Expected: all commands present in `report/index.html`; fix reds by inspecting each `command.json`'s `actual` vs `expected` and the per-command `events.log`/screenshot.
- [ ] **Step 3:** Commit per logical group (gestures, text, lifecycle, tree, waits) — multiple commits.

```bash
git add maestro-client/src/conformance
git commit -m "feat(conformance): remaining Tier-A behaviors on native (gestures/text/tree/lifecycle/waits)"
```

---

## Task 16: Run native fixture across all supported APIs (24–36)

**Files:**
- Modify: `device/FreshAvdProvider.kt` only if an API-specific quirk needs handling (e.g. API 33+ `RECEIVER_EXPORTED` already handled in the fixture; API 24 IME availability).

**Interfaces:** none new — this is an integration milestone.

- [ ] **Step 1:** Run the full matrix on native across all APIs (provisioned):
  `./gradlew :maestro-client:driverConformance --args="--api 24..36 --framework native --out ./report"`
  Expected: one AVD per API created/booted/torn down **sequentially**; `report/index.html` shows a row per `apiNN-native`.
- [ ] **Step 2:** Triage reds. Expected real per-API issues to handle (document the resolution in the report or a `KNOWN.md`):
  - API 29 Apple-Silicon screenshot/screenrecord quirks (mark artifact unavailable, don't fail the oracle).
  - IME availability on `google_apis` images (GBoard pin; if absent, the keyboard commands legitimately skip — record as skipped, not failed).
  - `RECEIVER_EXPORTED` (API 33+) — already handled in Task 10.
- [ ] **Step 3:** Commit any provider/fixture fixes.

```bash
git add maestro-client/src/conformance fixtures
git commit -m "fix(conformance): per-API quirks (IME availability, API29 capture, receiver export) for native matrix"
```

---

## Task 17: REVIEW GATE — native fixture complete

**This is a human review checkpoint, not code.**

- [ ] **Step 1:** Generate a fresh full-matrix report: `./gradlew :maestro-client:driverConformance --args="--api 24..36 --framework native --record all --out ./report"`.
- [ ] **Step 2:** Open `report/index.html`; confirm every Tier-A command has a verdict across all APIs, each red drills into `command.json` + artifacts.
- [ ] **Step 3:** Request code review of: the harness core (Tasks 1–8), the native fixture (Tasks 9–10, 14), the behaviors (Tasks 11–15). Use `superpowers:requesting-code-review`.
- [ ] **Step 4:** Address review feedback before starting any further fixture.
- [ ] **Step 5:** Only after sign-off, proceed to the next fixture (Compose) as a **separate plan** with the identical shape: new `fixtures/compose/` module implementing the same contract; **the harness and all behaviors are reused unchanged** (write-once premise, §5.2).

---

## Subsequent fixtures (separate plans, post-review)

Each of Compose → React Native → Flutter → WebView is its own plan that:
1. Creates `fixtures/<framework>/` implementing the §5 contract (same screens, same element ids, same `MAESTRO_FIXTURE` events) — for non-native frameworks, the **native `Log.d` bridge** (B1): Flutter `MethodChannel`, RN native module, WebView `@JavascriptInterface`.
2. Adds a `FixtureCatalog.byName` entry + bundles the APK.
3. Runs `--framework <fw>` on one API, then `--api 24..36`.
4. Ends with its own review gate.
The harness, behaviors, provider, reader, and reporter are **not modified** — only a new fixture is added.

---

## Self-Review (completed)

- **Spec coverage (Phase 1):** §3 architecture pieces → Tasks 5–8, 12 (CommandBehavior, LogcatEventReader, DeviceProvider, Reporter, runner). §4 lifecycle/oracle classes → Tasks 5, 11, 12, 13. §4.2/§4.3 catalogue → Tasks 11, 13, 15. §5 contract (epoch/seq, native bridge, screens, ids, MARK) → Tasks 4, 9, 10, 14. §5.2 coverage classes → `Coverage` in Tasks 5/15. §6 provisioning (fresh AVD, GBoard pin, cleanup, preflight) → Tasks 7, 8. §7 single-device sequential → Task 12 runner. §8 reporting → Task 6. §10 phasing → the Stage/review-gate structure. Later phases (Tier B/C, iOS) explicitly out of scope.
- **Placeholder scan:** every code step has complete code; the two intentional "implementer note" callouts (TreeBounds attribute key in Task 11; per-API triage in Task 16) are verification instructions against a live device, not missing code. Task 14 and Task 15 enumerate concrete per-item specs rather than full code for ~20 near-identical files (a deliberate DRY/anti-bloat choice; the pattern is shown fully in Tasks 10/11/13).
- **Type consistency:** `FixtureEvent`, `Watermark`, `CommandOutcome`, `CommandBehavior`, `BehaviorContext`, `DeviceHandle`, `CommandRecord`, `Coverage`, `OracleKind` names are used identically across tasks. `markWatermark` declared in Task 5, implemented in Task 12. `AndroidDriver` constructor/`open()`/`tap`/`takeScreenshot` match the real signatures gathered from the codebase.
