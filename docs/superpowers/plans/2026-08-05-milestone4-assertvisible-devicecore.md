# Milestone 4 — Route one `assertVisible` through maestro-device-core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make one standalone `assertVisible:` step inside a real Maestro flow (run via `maestro test`, iOS simulator) get its pass/fail verdict from maestro-device-core's `inspect()` instead of legacy's resolver — with legacy's own runner co-resident — while `when:`/`while:` guards stay entirely on legacy.

**Architecture:** Seam C (migrate-the-command). Legacy's `Orchestra.assertConditionCommand` handler gains an optional, injected `DeviceCoreAssertRouter`. When present and the condition is a *standalone* single-selector `visible:`/`notVisible:` with a text selector on iOS, the handler asks device-core for the verdict (`IosDeviceProvider().connect(TargetSelector(TargetId.IOS_SIM)).screen.getByText(...).inspect()`) and maps the returned `ElementEvidence` to pass/fail via a pure adapter. Device-core resolves with its own `XcuiSnapshotLocateStrategy`; it never emits a legacy `TreeNode` and never touches legacy's `findElement`/`buildFilter`. Every other `evaluateCondition` caller (`runScript`/`runFlow`/`repeat`) is untouched, so `when:`/`while:` remain legacy.

**Tech Stack:** Kotlin (JVM 17), Gradle 8.13, JUnit Jupiter 5 + Google Truth + MockK (Maestro's existing test stack). Dependency: maestro-device-core `:prototype` (+ `:drivers-core`), Kotlin 2.2.20 / JVM 21 / Gradle 9.5.1, consumed via `publishToMavenLocal`.

## Global Constraints

- **Seam location (current checkout, base `e08f33ac`):** the cut is `Orchestra.assertConditionCommand` — `maestro-orchestra/src/main/java/maestro/orchestra/Orchestra.kt:513`, reached from the dispatch arm `is AssertConditionCommand -> assertConditionCommand(command)` at `Orchestra.kt:411`. `evaluateCondition` is `Orchestra.kt:1015` with **exactly four callers**: `assertConditionCommand` (`:523`), `runScriptCommand` (`:724`), the repeat handler's `checkCondition()` (`:~906`), `runFlowCommand` (`:1008`). Re-locate by structure before editing; do not trust these numbers if the file has drifted.
- **Only standalone asserts route.** `when:`/`while:`/`runScript` reach `evaluateCondition` from their own handlers and must never see the router. This is the whole point of cutting at the handler, not inside `evaluateCondition`.
- **iOS simulator only.** Run through the legacy CLI runner: `maestro test <flow>.yaml`.
- **Text selector only.** device-core's iOS `getById` throws `NotImplementedError` today (`IosLocator`/`XcuiSnapshotLocateStrategy` only realize `Selector.Text`). The router must decline `id` selectors (they stay on legacy). This is a deliberate deviation from the spec's "Text or `id`" wording, grounded in the current device-core code.
- **visible-proxy = resolved + on-screen MEASURED bounds.** A pass requires `Resolution.Resolved` AND a `MEASURED` `Rect` with positive area whose box lies within the device's point-size. No faithful `visible` pillar exists on iOS yet (owed downstream, per spec).
- **Transient session is sufficient.** device-core registers → serves one `inspect()` → releases, per assert. No continuous session.
- **Ports:** device-core `127.0.0.1:8792` (raw line-JSON `SocketServer`); legacy `127.0.0.1:22087` (FlyingFox HTTP, `maestro-driver-ios`); fixture control `8795` (only if a scenario must be staged). Disjoint and non-contending.
- **Additivity:** every new Orchestra constructor param defaults to `null`/off. No existing Orchestra caller or test changes behavior when device-core is not wired.
- **Bundle-under-test is passed to device-core out-of-band** via system property `devicecore.ios.bundleId` (or env `DEVICECORE_IOS_BUNDLE_ID`). The router sets the system property from the flow's `appId` before `connect()`.

---

## File Structure

**New (this Maestro repo):**
- `maestro-orchestra/src/main/java/maestro/orchestra/devicecore/AssertVisibleVerdict.kt` — pure adapter: `ElementEvidence` + mode + screen dims → Boolean; the visible-proxy lives here. Zero device dependency.
- `maestro-orchestra/src/main/java/maestro/orchestra/devicecore/DeviceCoreRouting.kt` — `canRoute(Condition)` predicate + `ElementSelector`→text-query mapping (`RoutedQuery`). Pure.
- `maestro-orchestra/src/main/java/maestro/orchestra/devicecore/DeviceCoreAssertRouter.kt` — the integration seam: holds a `DeviceProvider` factory + appId, runs connect→getByText→nth→inspect, applies the adapter. Throws `DeviceCoreUnavailable` on infra failure.
- Test mirrors under `maestro-orchestra/src/test/kotlin/maestro/orchestra/devicecore/` (`AssertVisibleVerdictTest.kt`, `DeviceCoreRoutingTest.kt`, `DeviceCoreAssertRouterTest.kt`) plus a shared `FakeDeviceProvider.kt`.
- `prototypes/milestone4/flow.yaml`, `prototypes/milestone4/flow-negative.yaml`, `prototypes/milestone4/bringup.md` — the bespoke flow, the negative control, and the co-resident bring-up runbook.

**Modified (this Maestro repo):**
- `settings.gradle.kts` — add `mavenLocal()` to `dependencyResolutionManagement.repositories`.
- `maestro-orchestra/build.gradle.kts` — add `implementation("dev.mobile.devicecore:prototype:<ver>")`.
- `maestro-orchestra/src/main/java/maestro/orchestra/Orchestra.kt` — new nullable ctor param + the seam edit in `assertConditionCommand`.
- `maestro-cli/src/main/java/maestro/cli/runner/TestSuiteInteractor.kt:190` — env-gated router construction, passed to `Orchestra(...)`.

**Modified (maestro-device-core worktree — spike edits, minimal):**
- `prototype/build.gradle.kts`, `drivers/build.gradle.kts` — add `maven-publish` + `group`/`version`, force `jvmTarget = JVM_17`.

---

### Task 0: Cross-repo dependency — publish device-core to mavenLocal and consume it

**Why mavenLocal, not a composite build:** device-core pins **Gradle 9.5.1 / Kotlin 2.2.20 / JVM toolchain 21**; Maestro is **Gradle 8.13 / Kotlin 2.2.0 / JVM 17**. A composite `includeBuild` would force device-core to build under Maestro's Gradle 8.13 (the included build's own wrapper is ignored), contradicting its pinned 9.5.1 and likely failing on plugin compatibility. Publishing to mavenLocal lets device-core build itself with its own Gradle 9.5.1 and hand Maestro plain jars. The only cross-boundary constraint left is bytecode target — force device-core's two modules to emit JVM-17 bytecode so Maestro-17 can consume them.

**Files:**
- Modify (device-core worktree): `prototype/build.gradle.kts`, `drivers/build.gradle.kts`
- Modify: `settings.gradle.kts`
- Modify: `maestro-orchestra/build.gradle.kts`
- Test: `maestro-orchestra/src/test/kotlin/maestro/orchestra/devicecore/DeviceCoreClasspathTest.kt`

**Interfaces:**
- Produces (device-core, once published): Maven coordinates `dev.mobile.devicecore:prototype:0.0.0-m4` and `dev.mobile.devicecore:drivers-core:0.0.0-m4`, JVM-17 bytecode.
- Produces (Maestro): `maestro-orchestra` compiles against `dev.mobile.devicecore.prototype.api.*`.

- [ ] **Step 1: Add publishing + JVM-17 target to device-core's two modules.**

In the device-core worktree, in **both** `prototype/build.gradle.kts` and `drivers/build.gradle.kts`, add the `maven-publish` plugin, coordinates, and a JVM-17 override (the toolchain stays 21 — only the emitted bytecode target drops to 17):

```kotlin
plugins {
    // ...existing plugins...
    `maven-publish`
}

group = "dev.mobile.devicecore"
version = "0.0.0-m4"

// keep jvmToolchain(21) for the compile JDK, but emit 17-compatible bytecode
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") { from(components["java"]) }
    }
}
```

- [ ] **Step 2: Publish to mavenLocal (using device-core's own Gradle).**

Run from the device-core worktree root (`/Users/stevieclifton/codes/worktrees/maestro-device-core/coresidence-proof`):

```bash
./gradlew :drivers-core:publishToMavenLocal :prototype:publishToMavenLocal
```

Expected: two artifact trees under `~/.m2/repository/dev/mobile/devicecore/{prototype,drivers-core}/0.0.0-m4/`.

- [ ] **Step 3: Verify the published bytecode is JVM 17 (class file major 61).**

```bash
cd ~/.m2/repository/dev/mobile/devicecore/prototype/0.0.0-m4
unzip -o prototype-0.0.0-m4.jar -d /tmp/dc-check >/dev/null
javap -v -cp /tmp/dc-check 'dev.mobile.devicecore.prototype.api.ElementEvidence' | grep -m1 'major version'
```

Expected: `major version: 61`. If it prints `65`, Step 1's `jvmTarget` override did not take — fix before proceeding (Maestro-17 cannot consume a v65 jar).

- [ ] **Step 4: Wire Maestro to consume it.**

In `settings.gradle.kts`, add `mavenLocal()` as the *first* repository inside `dependencyResolutionManagement { repositories { ... } }`:

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal()
        google()
        mavenCentral()
    }
}
```

In `maestro-orchestra/build.gradle.kts`, add to `dependencies`:

```kotlin
    implementation("dev.mobile.devicecore:prototype:0.0.0-m4")
```

(`drivers-core` arrives transitively at runtime via prototype's POM; orchestra only compiles against the `prototype.api` types.)

- [ ] **Step 5: Write the classpath proof test.**

`maestro-orchestra/src/test/kotlin/maestro/orchestra/devicecore/DeviceCoreClasspathTest.kt`:

```kotlin
package maestro.orchestra.devicecore

import com.google.common.truth.Truth.assertThat
import dev.mobile.devicecore.prototype.api.ElementEvidence
import dev.mobile.devicecore.prototype.api.Resolution
import dev.mobile.devicecore.prototype.api.ResolvedChannel
import dev.mobile.devicecore.prototype.api.Actionability
import dev.mobile.devicecore.prototype.api.Signal
import dev.mobile.devicecore.prototype.api.Sourced
import dev.mobile.devicecore.prototype.api.Rect
import dev.mobile.devicecore.prototype.api.EvidenceSource
import org.junit.jupiter.api.Test

class DeviceCoreClasspathTest {
    @Test
    fun `device-core api types are on the compile classpath`() {
        val ua = Signal(false, EvidenceSource.UNAVAILABLE)
        val evidence = ElementEvidence(
            target = "Login",
            resolution = Resolution.Resolved(ResolvedChannel.TEXT),
            actionability = Actionability(ua, ua, Signal(true, EvidenceSource.MEASURED), ua, ua),
            bounds = Sourced(Rect(x = 122, y = 160, width = 148, height = 26), EvidenceSource.MEASURED),
        )
        assertThat(evidence.resolution).isInstanceOf(Resolution.Resolved::class.java)
        assertThat(evidence.bounds.value?.width).isEqualTo(148)
    }
}
```

- [ ] **Step 6: Run it.**

Run: `./gradlew :maestro-orchestra:test --tests 'maestro.orchestra.devicecore.DeviceCoreClasspathTest'`
Expected: PASS. (If it fails to compile with "cannot access class ... incompatible version," recheck Step 3.)

- [ ] **Step 7: Commit.**

```bash
git add settings.gradle.kts maestro-orchestra/build.gradle.kts maestro-orchestra/src/test/kotlin/maestro/orchestra/devicecore/DeviceCoreClasspathTest.kt
git commit -m "build: consume maestro-device-core prototype from mavenLocal (m4)"
```

---

### Task 1: The pure verdict adapter (`AssertVisibleVerdict`)

**Files:**
- Create: `maestro-orchestra/src/main/java/maestro/orchestra/devicecore/AssertVisibleVerdict.kt`
- Test: `maestro-orchestra/src/test/kotlin/maestro/orchestra/devicecore/AssertVisibleVerdictTest.kt`

**Interfaces:**
- Consumes: `ElementEvidence`, `Resolution`, `EvidenceSource`, `Rect` from Task 0.
- Produces: `enum class AssertMode { VISIBLE, NOT_VISIBLE }`; `class DeviceCoreUnavailable(msg: String) : RuntimeException(msg)`; `object AssertVisibleVerdict { fun isVisibleProxy(evidence: ElementEvidence, screenWidthPts: Int, screenHeightPts: Int): Boolean; fun pass(evidence: ElementEvidence, mode: AssertMode, screenWidthPts: Int, screenHeightPts: Int): Boolean }`.

- [ ] **Step 1: Write the failing tests.**

`AssertVisibleVerdictTest.kt`:

```kotlin
package maestro.orchestra.devicecore

import com.google.common.truth.Truth.assertThat
import dev.mobile.devicecore.prototype.api.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AssertVisibleVerdictTest {
    private val ua = Signal(false, EvidenceSource.UNAVAILABLE)
    private fun evidence(res: Resolution, bounds: Sourced<Rect>) =
        ElementEvidence("t", res, Actionability(ua, ua, ua, ua, ua), bounds)
    private fun measured(x: Int, y: Int, w: Int, h: Int) =
        Sourced(Rect(x, y, w, h), EvidenceSource.MEASURED)

    private val W = 393
    private val H = 852

    @Test fun `resolved with on-screen measured bounds is visible`() {
        val e = evidence(Resolution.Resolved(ResolvedChannel.TEXT), measured(122, 160, 148, 26))
        assertThat(AssertVisibleVerdict.pass(e, AssertMode.VISIBLE, W, H)).isTrue()
    }

    @Test fun `absent is not visible`() {
        val e = evidence(Resolution.Absent(SearchedSurface.WHOLE_SCREEN), Sourced(null, EvidenceSource.UNAVAILABLE))
        assertThat(AssertVisibleVerdict.pass(e, AssertMode.VISIBLE, W, H)).isFalse()
    }

    @Test fun `ambiguous is not visible`() {
        val e = evidence(Resolution.Ambiguous(3), Sourced(null, EvidenceSource.UNAVAILABLE))
        assertThat(AssertVisibleVerdict.pass(e, AssertMode.VISIBLE, W, H)).isFalse()
    }

    @Test fun `resolved but off-screen (below viewport) is not visible`() {
        val e = evidence(Resolution.Resolved(ResolvedChannel.TEXT), measured(10, 900, 100, 40))
        assertThat(AssertVisibleVerdict.pass(e, AssertMode.VISIBLE, W, H)).isFalse()
    }

    @Test fun `resolved but zero-area is not visible`() {
        val e = evidence(Resolution.Resolved(ResolvedChannel.TEXT), measured(10, 10, 0, 0))
        assertThat(AssertVisibleVerdict.pass(e, AssertMode.VISIBLE, W, H)).isFalse()
    }

    @Test fun `resolved but bounds only INFERRED is not visible`() {
        val e = evidence(Resolution.Resolved(ResolvedChannel.TEXT), Sourced(Rect(1,1,10,10), EvidenceSource.INFERRED))
        assertThat(AssertVisibleVerdict.pass(e, AssertMode.VISIBLE, W, H)).isFalse()
    }

    @Test fun `notVisible passes when element is absent`() {
        val e = evidence(Resolution.Absent(SearchedSurface.WHOLE_SCREEN), Sourced(null, EvidenceSource.UNAVAILABLE))
        assertThat(AssertVisibleVerdict.pass(e, AssertMode.NOT_VISIBLE, W, H)).isTrue()
    }

    @Test fun `notVisible fails when element is visible`() {
        val e = evidence(Resolution.Resolved(ResolvedChannel.TEXT), measured(122, 160, 148, 26))
        assertThat(AssertVisibleVerdict.pass(e, AssertMode.NOT_VISIBLE, W, H)).isFalse()
    }

    @Test fun `unavailable throws for both modes, never a silent verdict`() {
        val e = evidence(Resolution.Unavailable, Sourced(null, EvidenceSource.UNAVAILABLE))
        assertThrows<DeviceCoreUnavailable> { AssertVisibleVerdict.pass(e, AssertMode.VISIBLE, W, H) }
        assertThrows<DeviceCoreUnavailable> { AssertVisibleVerdict.pass(e, AssertMode.NOT_VISIBLE, W, H) }
    }
}
```

- [ ] **Step 2: Run to verify failure.**

Run: `./gradlew :maestro-orchestra:test --tests 'maestro.orchestra.devicecore.AssertVisibleVerdictTest'`
Expected: FAIL — `AssertVisibleVerdict` / `AssertMode` / `DeviceCoreUnavailable` unresolved.

- [ ] **Step 3: Implement.**

`AssertVisibleVerdict.kt`:

```kotlin
package maestro.orchestra.devicecore

import dev.mobile.devicecore.prototype.api.ElementEvidence
import dev.mobile.devicecore.prototype.api.EvidenceSource
import dev.mobile.devicecore.prototype.api.Resolution

enum class AssertMode { VISIBLE, NOT_VISIBLE }

/** Raised when device-core could not decide (socket refused, driver down). Never a pass or fail. */
class DeviceCoreUnavailable(message: String) : RuntimeException(message)

object AssertVisibleVerdict {

    /** Milestone-4 visible-proxy: resolved + a MEASURED, positive-area box fully inside the screen. */
    fun isVisibleProxy(evidence: ElementEvidence, screenWidthPts: Int, screenHeightPts: Int): Boolean {
        if (evidence.resolution !is Resolution.Resolved) return false
        val bounds = evidence.bounds
        if (bounds.source != EvidenceSource.MEASURED) return false
        val r = bounds.value ?: return false
        if (r.width <= 0 || r.height <= 0) return false
        if (r.x < 0 || r.y < 0) return false
        if (r.x + r.width > screenWidthPts || r.y + r.height > screenHeightPts) return false
        return true
    }

    fun pass(evidence: ElementEvidence, mode: AssertMode, screenWidthPts: Int, screenHeightPts: Int): Boolean {
        if (evidence.resolution is Resolution.Unavailable) {
            throw DeviceCoreUnavailable(
                "device-core could not resolve '${evidence.target}' (Resolution.Unavailable) — " +
                    "this is an infrastructure failure, not an assertion verdict."
            )
        }
        val visible = isVisibleProxy(evidence, screenWidthPts, screenHeightPts)
        return when (mode) {
            AssertMode.VISIBLE -> visible
            AssertMode.NOT_VISIBLE -> !visible
        }
    }
}
```

- [ ] **Step 4: Run to verify pass.**

Run: `./gradlew :maestro-orchestra:test --tests 'maestro.orchestra.devicecore.AssertVisibleVerdictTest'`
Expected: PASS (all 10).

- [ ] **Step 5: Commit.**

```bash
git add maestro-orchestra/src/main/java/maestro/orchestra/devicecore/AssertVisibleVerdict.kt maestro-orchestra/src/test/kotlin/maestro/orchestra/devicecore/AssertVisibleVerdictTest.kt
git commit -m "feat(devicecore): pure ElementEvidence -> assertVisible verdict adapter"
```

---

### Task 2: Routability predicate + selector mapping (`DeviceCoreRouting`)

**Files:**
- Create: `maestro-orchestra/src/main/java/maestro/orchestra/devicecore/DeviceCoreRouting.kt`
- Test: `maestro-orchestra/src/test/kotlin/maestro/orchestra/devicecore/DeviceCoreRoutingTest.kt`

**Interfaces:**
- Consumes: `maestro.orchestra.Condition`, `maestro.orchestra.ElementSelector`; device-core `Match`.
- Produces: `data class RoutedQuery(val text: String, val match: Match, val index: Int?, val mode: AssertMode)`; `object DeviceCoreRouting { fun route(condition: Condition): RoutedQuery? }`. `route` returns non-null iff the condition is a standalone single text selector routable on iOS; null means "leave on legacy."

- [ ] **Step 1: Write the failing tests.**

`DeviceCoreRoutingTest.kt`:

```kotlin
package maestro.orchestra.devicecore

import com.google.common.truth.Truth.assertThat
import dev.mobile.devicecore.prototype.api.Match
import maestro.orchestra.Condition
import maestro.orchestra.ElementSelector
import org.junit.jupiter.api.Test

class DeviceCoreRoutingTest {

    @Test fun `plain visible text selector routes as EXACT VISIBLE`() {
        val q = DeviceCoreRouting.route(Condition(visible = ElementSelector(textRegex = "Welcome home")))
        assertThat(q).isEqualTo(RoutedQuery("Welcome home", Match.EXACT, null, AssertMode.VISIBLE))
    }

    @Test fun `plain notVisible text selector routes as NOT_VISIBLE`() {
        val q = DeviceCoreRouting.route(Condition(notVisible = ElementSelector(textRegex = "Spinner")))
        assertThat(q?.mode).isEqualTo(AssertMode.NOT_VISIBLE)
    }

    @Test fun `text selector with index maps to nth`() {
        val q = DeviceCoreRouting.route(Condition(visible = ElementSelector(textRegex = "Row", index = "2")))
        assertThat(q?.index).isEqualTo(2)
    }

    @Test fun `id selector does NOT route (getById unimplemented on iOS)`() {
        assertThat(DeviceCoreRouting.route(Condition(visible = ElementSelector(idRegex = "login_btn")))).isNull()
    }

    @Test fun `text selector with regex metacharacters does NOT route`() {
        assertThat(DeviceCoreRouting.route(Condition(visible = ElementSelector(textRegex = "Item .*")))).isNull()
    }

    @Test fun `relational or trait constraints do NOT route`() {
        val below = ElementSelector(textRegex = "A", below = ElementSelector(textRegex = "B"))
        assertThat(DeviceCoreRouting.route(Condition(visible = below))).isNull()
    }

    @Test fun `condition with both visible and notVisible does NOT route`() {
        val c = Condition(visible = ElementSelector(textRegex = "A"), notVisible = ElementSelector(textRegex = "B"))
        assertThat(DeviceCoreRouting.route(c)).isNull()
    }

    @Test fun `condition with a platform guard or scriptCondition does NOT route`() {
        assertThat(DeviceCoreRouting.route(Condition(scriptCondition = "x"))).isNull()
        assertThat(DeviceCoreRouting.route(Condition(visible = ElementSelector(textRegex = "A"),
            platform = maestro.device.Platform.IOS))).isNull()
    }

    @Test fun `empty condition does NOT route`() {
        assertThat(DeviceCoreRouting.route(Condition())).isNull()
    }
}
```

- [ ] **Step 2: Run to verify failure.**

Run: `./gradlew :maestro-orchestra:test --tests 'maestro.orchestra.devicecore.DeviceCoreRoutingTest'`
Expected: FAIL — `DeviceCoreRouting` / `RoutedQuery` unresolved.

- [ ] **Step 3: Implement.**

`DeviceCoreRouting.kt`:

```kotlin
package maestro.orchestra.devicecore

import dev.mobile.devicecore.prototype.api.Match
import maestro.orchestra.Condition
import maestro.orchestra.ElementSelector

data class RoutedQuery(
    val text: String,
    val match: Match,
    val index: Int?,
    val mode: AssertMode,
)

object DeviceCoreRouting {

    // A literal is anything with no regex metacharacters — device-core matches literal text, not regex.
    private val REGEX_METACHARS = Regex("""[.*+?\[\]{}()^$|\\]""")

    fun route(condition: Condition): RoutedQuery? {
        // Must be a bare standalone assert: no platform guard, no script, exactly one of visible/notVisible.
        if (condition.platform != null || condition.scriptCondition != null) return null
        val visible = condition.visible
        val notVisible = condition.notVisible
        val (selector, mode) = when {
            visible != null && notVisible == null -> visible to AssertMode.VISIBLE
            notVisible != null && visible == null -> notVisible to AssertMode.NOT_VISIBLE
            else -> return null
        }
        val query = toTextQuery(selector) ?: return null
        return query.copy(mode = mode)
    }

    /** Only a plain literal-text selector (optionally + index) is routable. Everything else stays on legacy. */
    private fun toTextQuery(s: ElementSelector): RoutedQuery? {
        val text = s.textRegex ?: return null
        if (s.idRegex != null) return null
        if (REGEX_METACHARS.containsMatchIn(text)) return null
        // Reject any constraint device-core's text strategy can't honor.
        if (s.size != null || s.below != null || s.above != null || s.leftOf != null || s.rightOf != null ||
            s.containsChild != null || s.containsDescendants != null || s.traits != null ||
            s.enabled != null || s.selected != null || s.checked != null || s.focused != null ||
            s.childOf != null || s.css != null
        ) return null
        val index = s.index?.toIntOrNull()
        if (s.index != null && index == null) return null
        return RoutedQuery(text = text, match = Match.EXACT, index = index, mode = AssertMode.VISIBLE)
    }
}
```

- [ ] **Step 4: Run to verify pass.**

Run: `./gradlew :maestro-orchestra:test --tests 'maestro.orchestra.devicecore.DeviceCoreRoutingTest'`
Expected: PASS (all 10 assertions across the 9 tests).

- [ ] **Step 5: Commit.**

```bash
git add maestro-orchestra/src/main/java/maestro/orchestra/devicecore/DeviceCoreRouting.kt maestro-orchestra/src/test/kotlin/maestro/orchestra/devicecore/DeviceCoreRoutingTest.kt
git commit -m "feat(devicecore): routability predicate + text-selector mapping"
```

---

### Task 3: The router (`DeviceCoreAssertRouter`) — the device-core call

**Files:**
- Create: `maestro-orchestra/src/main/java/maestro/orchestra/devicecore/DeviceCoreAssertRouter.kt`
- Create (test): `maestro-orchestra/src/test/kotlin/maestro/orchestra/devicecore/FakeDeviceProvider.kt`
- Test: `maestro-orchestra/src/test/kotlin/maestro/orchestra/devicecore/DeviceCoreAssertRouterTest.kt`

**Interfaces:**
- Consumes: device-core `DeviceProvider`, `Device`, `Screen`, `Locator`, `TargetSelector`, `TargetId`, `Match`, `ElementEvidence` (public interfaces/types); Task 1 `AssertVisibleVerdict`/`AssertMode`; Task 2 `DeviceCoreRouting`/`RoutedQuery`; `maestro.orchestra.Condition`.
- Produces:
  ```kotlin
  class DeviceCoreAssertRouter(
      private val appId: String,
      private val providerFactory: () -> DeviceProvider = { IosDeviceProvider() },
  ) {
      fun canRoute(condition: Condition): Boolean
      suspend fun evaluate(condition: Condition, screenWidthPts: Int, screenHeightPts: Int): Boolean
  }
  ```
  `evaluate` throws `IllegalArgumentException` if called on a non-routable condition (guard with `canRoute` first), and `DeviceCoreUnavailable` on infra failure.

- [ ] **Step 1: Write the fake provider.**

`FakeDeviceProvider.kt` — implements the public device-core interfaces so the router is testable with no simulator:

```kotlin
package maestro.orchestra.devicecore

import dev.mobile.devicecore.prototype.api.*

class FakeDeviceProvider(private val evidenceFor: (Selector) -> ElementEvidence) : DeviceProvider {
    var lastConnectedTarget: TargetSelector? = null
    var lastSelector: Selector? = null

    override suspend fun connect(selector: TargetSelector): Device {
        lastConnectedTarget = selector
        return object : Device {
            override val screen: Screen = object : Screen {
                override fun getById(value: String): Locator = locator(Selector.Id(value))
                override fun getByText(value: String, match: Match): Locator = locator(Selector.Text(value, match))
            }
        }
    }

    private fun locator(sel: Selector): Locator = object : Locator {
        override val selector: Selector = sel
        override suspend fun tap(): ActionEvidence = throw NotImplementedError("fake: no tap")
        override suspend fun inspect(): ElementEvidence { lastSelector = sel; return evidenceFor(sel) }
        override fun nth(index: Int): Locator = locator(Selector.Nth(sel, index))
    }
}
```

> Note for the implementer: confirm the exact interface members of `DeviceProvider`/`Device`/`Screen`/`Locator` against the published `prototype.api` (see Task 0's report). If a member signature differs, adjust the fake — do not change the router to fit a wrong fake.

- [ ] **Step 2: Write the failing router tests.**

`DeviceCoreAssertRouterTest.kt`:

```kotlin
package maestro.orchestra.devicecore

import com.google.common.truth.Truth.assertThat
import dev.mobile.devicecore.prototype.api.*
import kotlinx.coroutines.runBlocking
import maestro.orchestra.Condition
import maestro.orchestra.ElementSelector
import org.junit.jupiter.api.Test

class DeviceCoreAssertRouterTest {
    private val ua = Signal(false, EvidenceSource.UNAVAILABLE)
    private fun resolved(x: Int, y: Int, w: Int, h: Int) = ElementEvidence(
        "t", Resolution.Resolved(ResolvedChannel.TEXT),
        Actionability(ua, ua, Signal(true, EvidenceSource.MEASURED), ua, ua),
        Sourced(Rect(x, y, w, h), EvidenceSource.MEASURED),
    )
    private fun absent() = ElementEvidence(
        "t", Resolution.Absent(SearchedSurface.WHOLE_SCREEN),
        Actionability(ua, ua, ua, ua, ua), Sourced(null, EvidenceSource.UNAVAILABLE),
    )

    @Test fun `canRoute mirrors DeviceCoreRouting`() {
        val r = DeviceCoreAssertRouter("com.x") { FakeDeviceProvider { resolved(1,1,10,10) } }
        assertThat(r.canRoute(Condition(visible = ElementSelector(textRegex = "Hi")))).isTrue()
        assertThat(r.canRoute(Condition(visible = ElementSelector(idRegex = "hi")))).isFalse()
    }

    @Test fun `evaluate visible on a resolved on-screen element returns true, targets IOS_SIM by text`() {
        val fake = FakeDeviceProvider { resolved(122, 160, 148, 26) }
        val r = DeviceCoreAssertRouter("com.x") { fake }
        val pass = runBlocking { r.evaluate(Condition(visible = ElementSelector(textRegex = "Welcome")), 393, 852) }
        assertThat(pass).isTrue()
        assertThat(fake.lastConnectedTarget?.target).isEqualTo(TargetId.IOS_SIM)
        assertThat(fake.lastSelector).isEqualTo(Selector.Text("Welcome", Match.EXACT))
        assertThat(System.getProperty("devicecore.ios.bundleId")).isEqualTo("com.x")
    }

    @Test fun `evaluate visible on an absent element returns false (the negative control)`() {
        val r = DeviceCoreAssertRouter("com.x") { FakeDeviceProvider { absent() } }
        val pass = runBlocking { r.evaluate(Condition(visible = ElementSelector(textRegex = "Nope")), 393, 852) }
        assertThat(pass).isFalse()
    }

    @Test fun `evaluate applies nth for an indexed selector`() {
        val fake = FakeDeviceProvider { resolved(1, 1, 10, 10) }
        val r = DeviceCoreAssertRouter("com.x") { fake }
        runBlocking { r.evaluate(Condition(visible = ElementSelector(textRegex = "Row", index = "2")), 393, 852) }
        assertThat(fake.lastSelector).isEqualTo(Selector.Nth(Selector.Text("Row", Match.EXACT), 2))
    }
}
```

- [ ] **Step 3: Run to verify failure.**

Run: `./gradlew :maestro-orchestra:test --tests 'maestro.orchestra.devicecore.DeviceCoreAssertRouterTest'`
Expected: FAIL — `DeviceCoreAssertRouter` unresolved.

- [ ] **Step 4: Implement.**

`DeviceCoreAssertRouter.kt`:

```kotlin
package maestro.orchestra.devicecore

import dev.mobile.devicecore.prototype.api.DeviceProvider
import dev.mobile.devicecore.prototype.api.Locator
import dev.mobile.devicecore.prototype.api.TargetId
import dev.mobile.devicecore.prototype.api.TargetSelector
import dev.mobile.devicecore.prototype.api.adaptors.ios.IosDeviceProvider
import maestro.orchestra.Condition

/**
 * Routes a standalone assertVisible/assertNotVisible to maestro-device-core's inspect().
 * Transient session per call: connect -> getByText -> inspect -> map -> (session releases).
 */
class DeviceCoreAssertRouter(
    private val appId: String,
    private val providerFactory: () -> DeviceProvider = { IosDeviceProvider() },
) {
    fun canRoute(condition: Condition): Boolean = DeviceCoreRouting.route(condition) != null

    suspend fun evaluate(condition: Condition, screenWidthPts: Int, screenHeightPts: Int): Boolean {
        val query = DeviceCoreRouting.route(condition)
            ?: throw IllegalArgumentException("evaluate() called on a non-routable condition; guard with canRoute().")

        // device-core resolves the app-under-test from this system property (resolveBundleId()).
        System.setProperty("devicecore.ios.bundleId", appId)

        val evidence = try {
            val device = providerFactory().connect(TargetSelector(TargetId.IOS_SIM))
            val base: Locator = device.screen.getByText(query.text, query.match)
            val locator = query.index?.let { base.nth(it) } ?: base
            locator.inspect()
        } catch (e: DeviceCoreUnavailable) {
            throw e
        } catch (e: Exception) {
            throw DeviceCoreUnavailable("device-core inspect() failed for '${query.text}': ${e.message}")
        }

        return AssertVisibleVerdict.pass(evidence, query.mode, screenWidthPts, screenHeightPts)
    }
}
```

- [ ] **Step 5: Run to verify pass.**

Run: `./gradlew :maestro-orchestra:test --tests 'maestro.orchestra.devicecore.DeviceCoreAssertRouterTest'`
Expected: PASS.

- [ ] **Step 6: Commit.**

```bash
git add maestro-orchestra/src/main/java/maestro/orchestra/devicecore/DeviceCoreAssertRouter.kt maestro-orchestra/src/test/kotlin/maestro/orchestra/devicecore/FakeDeviceProvider.kt maestro-orchestra/src/test/kotlin/maestro/orchestra/devicecore/DeviceCoreAssertRouterTest.kt
git commit -m "feat(devicecore): assert router calling device-core inspect() via injectable provider"
```

---

### Task 4: The Orchestra seam edit

**Files:**
- Modify: `maestro-orchestra/src/main/java/maestro/orchestra/Orchestra.kt` (ctor ~`:131-163`; handler `:513`)
- Test: `maestro-orchestra/src/test/kotlin/maestro/orchestra/devicecore/OrchestraSeamTest.kt`

**Interfaces:**
- Consumes: Task 3 `DeviceCoreAssertRouter`.
- Produces: new Orchestra ctor param `deviceCoreAssertRouter: DeviceCoreAssertRouter? = null`; `assertConditionCommand` consults it for routable conditions only.

- [ ] **Step 1: Write the failing seam tests.**

`OrchestraSeamTest.kt` — a real router backed by Task 3's `FakeDeviceProvider` proves (a) a standalone assertVisible is routed (device-core's `inspect` is consulted), (b) a `runFlow` `when:` condition is NOT (device-core is never consulted). Checking `fake.lastSelector` avoids needing to make the router `open`. Use MockK for the `Maestro` facade.

```kotlin
package maestro.orchestra.devicecore

import com.google.common.truth.Truth.assertThat
import dev.mobile.devicecore.prototype.api.*
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import maestro.DeviceInfo
import maestro.Maestro
import maestro.device.Platform
import maestro.orchestra.*
import org.junit.jupiter.api.Test

class OrchestraSeamTest {
    private val ua = Signal(false, EvidenceSource.UNAVAILABLE)
    private fun resolvedOnScreen() = ElementEvidence(
        "t", Resolution.Resolved(ResolvedChannel.TEXT),
        Actionability(ua, ua, Signal(true, EvidenceSource.MEASURED), ua, ua),
        Sourced(Rect(122, 160, 148, 26), EvidenceSource.MEASURED),
    )
    private fun maestroStub(): Maestro = mockk(relaxed = true) {
        every { cachedDeviceInfo } returns DeviceInfo(Platform.IOS, 1179, 2556, 393, 852)
    }

    @Test fun `standalone assertVisible is routed to device-core`() {
        val fake = FakeDeviceProvider { resolvedOnScreen() }
        val orchestra = Orchestra(maestro = maestroStub(),
            deviceCoreAssertRouter = DeviceCoreAssertRouter("com.x") { fake })
        val cmd = MaestroCommand(assertConditionCommand =
            AssertConditionCommand(Condition(visible = ElementSelector(textRegex = "Welcome"))))
        val result = runBlocking { orchestra.runFlow(listOf(cmd)) }
        assertThat(result.success).isTrue()
        assertThat(fake.lastSelector).isEqualTo(Selector.Text("Welcome", Match.EXACT)) // device-core consulted
    }

    @Test fun `runFlow when-guard is NOT routed (stays on legacy)`() {
        val fake = FakeDeviceProvider { resolvedOnScreen() }
        val orchestra = Orchestra(maestro = maestroStub(),
            deviceCoreAssertRouter = DeviceCoreAssertRouter("com.x") { fake })
        val runFlow = MaestroCommand(runFlowCommand = RunFlowCommand(
            commands = emptyList(),
            condition = Condition(visible = ElementSelector(textRegex = "Welcome")),
        ))
        runBlocking { orchestra.runFlow(listOf(runFlow)) }
        assertThat(fake.lastSelector).isNull() // when: guard used legacy evaluateCondition, never device-core
    }
}
```

> `FakeDeviceProvider` is the Task 3 test helper (same test source set) — reuse it, don't duplicate it. Adjust `RunFlowCommand`'s constructor args to match its real signature (`maestro-orchestra-models/.../Commands.kt`).
>
> If a relaxed-MockK `Maestro` proves too brittle to drive `runFlow` (jsEngine init, listeners, artifact generation all fire), follow the Orchestra-construction patterns already in `maestro-test/src/test/kotlin/maestro/test/IntegrationTest.kt` (it builds Orchestra against a fake driver) rather than fighting the mock.

- [ ] **Step 2: Run to verify failure.**

Run: `./gradlew :maestro-orchestra:test --tests 'maestro.orchestra.devicecore.OrchestraSeamTest'`
Expected: FAIL — `deviceCoreAssertRouter` is not a constructor parameter.

- [ ] **Step 3: Add the constructor param.**

In `Orchestra.kt`, add to the constructor (after `flowController`, before `jsEngineFactory` ~`:152`), and make the router class `open` (Task 3):

```kotlin
    private val deviceCoreAssertRouter: maestro.orchestra.devicecore.DeviceCoreAssertRouter? = null,
```

- [ ] **Step 4: Edit `assertConditionCommand` (`:513`).**

Replace the single `if (!evaluateCondition(...))` gate with a router-aware verdict, keeping the exact same failure behavior:

```kotlin
    private suspend fun assertConditionCommand(command: AssertConditionCommand): Boolean {
        val timeout = (command.timeoutMs() ?: lookupTimeoutMs)
        val debugMessage = """
            ...unchanged...
        """.trimIndent()

        val router = deviceCoreAssertRouter
        val passed = if (router != null && router.canRoute(command.condition)) {
            val info = maestro.cachedDeviceInfo
            router.evaluate(command.condition, info.widthGrid, info.heightGrid)
        } else {
            evaluateCondition(command.condition, timeoutMs = timeout, commandOptional = command.optional)
        }

        if (!passed) {
            throw MaestroException.AssertionFailure(
                message = "Assertion is false: ${command.condition.description()}",
                hierarchyRoot = maestro.viewHierarchy().root,
                debugMessage = debugMessage
            )
        }
        return false
    }
```

> `widthGrid`/`heightGrid` are **points** on iOS (`DeviceInfo.toCommonDeviceInfo` maps `widthPoints`/`heightPoints` into them), matching device-core's point-based `Rect`. Do not use `widthPixels`/`heightPixels`.

- [ ] **Step 5: Run to verify pass.**

Run: `./gradlew :maestro-orchestra:test --tests 'maestro.orchestra.devicecore.OrchestraSeamTest'`
Expected: PASS — routed assert hits the spy once; the `when:` guard does not.

- [ ] **Step 6: Full orchestra suite (no regressions from the additive param).**

Run: `./gradlew :maestro-orchestra:test`
Expected: PASS. The new param defaults to `null`, so every existing test builds Orchestra unchanged and takes the legacy path.

- [ ] **Step 7: Commit.**

```bash
git add maestro-orchestra/src/main/java/maestro/orchestra/Orchestra.kt maestro-orchestra/src/test/kotlin/maestro/orchestra/devicecore/OrchestraSeamTest.kt
git commit -m "feat(orchestra): route standalone assertVisible to device-core at the AssertConditionCommand seam"
```

---

### Task 5: CLI wiring — env-gated router in `TestSuiteInteractor`

**Files:**
- Modify: `maestro-cli/src/main/java/maestro/cli/runner/TestSuiteInteractor.kt:190`

**Interfaces:**
- Consumes: Task 3/4 `DeviceCoreAssertRouter`, `Orchestra(deviceCoreAssertRouter = ...)`.
- Produces: when `MAESTRO_DEVICECORE_ASSERT=1` and the target is iOS, the CLI builds a router from the flow's `appId` and injects it. Otherwise `null` (pure legacy).

- [ ] **Step 1: Build and inject the router.**

At `TestSuiteInteractor.kt:190`, immediately before `val orchestra = Orchestra(`, derive the appId and gate:

```kotlin
                val deviceCoreRouter = if (
                    System.getenv("MAESTRO_DEVICECORE_ASSERT") == "1" &&
                    maestro.cachedDeviceInfo.platform == maestro.device.Platform.IOS
                ) {
                    val appId = YamlCommandReader.getConfig(commands)?.appId
                        ?: error("MAESTRO_DEVICECORE_ASSERT=1 requires an appId in the flow config")
                    maestro.orchestra.devicecore.DeviceCoreAssertRouter(appId = appId)
                } else null
```

Then pass it into the existing constructor call:

```kotlin
                val orchestra = Orchestra(
                    maestro = maestro,
                    artifactsDir = flowDir,
                    captureFullArtifacts = captureFullArtifacts,
                    listeners = listOf(CliConsoleListener(shardPrefix)),
                    deviceCoreAssertRouter = deviceCoreRouter,
                    onCommandFailed = { _, _, _ -> Orchestra.ErrorResolution.FAIL },
                    // ...unchanged onCommandGeneratedOutput...
                )
```

> Confirm the correct accessor for the flow's `appId` (`YamlCommandReader.getConfig(commands)?.appId` — same `getConfig` used at `runFlow`); adjust the import if `Platform` is already imported.

- [ ] **Step 2: Compile the CLI.**

Run: `./gradlew :maestro-cli:compileKotlin`
Expected: BUILD SUCCESSFUL. (This wiring is exercised end-to-end in Task 6; there is no cheap CLI unit test for the `maestro test` entrypoint, so verification is the compile here plus the live run in Task 6 — do not claim it works until Task 6 passes.)

- [ ] **Step 3: Commit.**

```bash
git add maestro-cli/src/main/java/maestro/cli/runner/TestSuiteInteractor.kt
git commit -m "feat(cli): env-gated device-core assert router injection (MAESTRO_DEVICECORE_ASSERT)"
```

---

### Task 6: The bespoke flow, co-resident bring-up, and the live proof

This is the milestone-4 acceptance itself — the parts that need a booted simulator and both runners. Not pytest-shaped, but each step has concrete pass evidence. Reuse the proven co-residence rig verbatim; do not reinvent bring-up.

**Files:**
- Create: `prototypes/milestone4/flow.yaml`, `prototypes/milestone4/flow-negative.yaml`, `prototypes/milestone4/bringup.md`

**Interfaces:**
- Consumes: everything above; the device-core rig at `/Users/stevieclifton/codes/worktrees/maestro-device-core/coresidence-proof` and its `evidence/watch-coresidence.sh`.

- [ ] **Step 1: Choose the target element (discovery — do not hardcode blind).**

Boot the proven sim and install/launch device-core's fixture the way `maestro test` will (plain launch, no conformance staging):

```bash
xcrun simctl boot 6921573F-D8AB-4AC7-A24C-BC700CD7345D   # or a locally-available iOS 16.4 sim UDID
```

Bring up device-core's driver server (Step 2), then enumerate what the fixture's *default* screen actually shows and pick an **unambiguously-visible, unique, literal** text. Two ways to enumerate:
- device-core: `IosDeviceProvider().connect(TargetSelector(TargetId.IOS_SIM)).screen.getByText("<candidate>", Match.EXACT).inspect()` and confirm `Resolution.Resolved` + on-screen MEASURED bounds; or
- legacy: `maestro hierarchy` against the launched app.

Record the chosen literal (call it `<TARGET_TEXT>`) and its bounds in `bringup.md`. Acceptance for the choice: `route(Condition(visible=ElementSelector(textRegex=<TARGET_TEXT>)))` is non-null (no regex metachars) **and** device-core resolves it on-screen. If the default screen has no such text, add legacy navigation steps (`tapOn`, etc.) to the flow to reach a screen that does, then pick there. device-core's `static-text-unique` cell is a known-resolvable candidate — reuse its text if it appears on the reachable screen.

- [ ] **Step 2: Bring up device-core's runner (8792), co-resident with legacy.**

From the rig root, start device-core's XCTest driver server exactly as the proof does (via `session.py`/`start_driver_server`, or directly):

```bash
# builds ConformanceDriverServer.xctestrun once (conformance/apps/ios-uikit/build.sh), then:
xcodebuild test-without-building \
  -xctestrun conformance/apps/ios-uikit/build/Build/Products/ConformanceDriverServer.xctestrun \
  -destination id=6921573F-D8AB-4AC7-A24C-BC700CD7345D
# wait until 8792 answers:
until nc -z -G1 127.0.0.1 8792; do sleep 1; done
```

Legacy's own runner (22087) is started automatically by `maestro test` in Step 4. Document both in `bringup.md`. Ports: device-core 8792, legacy 22087 (disjoint).

- [ ] **Step 3: Write the flows.**

`prototypes/milestone4/flow.yaml`:

```yaml
appId: dev.mobile.devicecore.conformance.uikit
---
- launchApp
# (optional legacy navigation steps here, if Step 1 required them)
- assertVisible: "<TARGET_TEXT>"     # routed to device-core when MAESTRO_DEVICECORE_ASSERT=1
```

`prototypes/milestone4/flow-negative.yaml` (negative control — an absent element):

```yaml
appId: dev.mobile.devicecore.conformance.uikit
---
- launchApp
- assertVisible: "This Text Is Absent 9F3K2Q"
```

- [ ] **Step 4: Run the positive flow through device-core, capturing co-residence.**

In one shell start the watcher (proves both runners live at the serving instant):

```bash
bash /Users/stevieclifton/codes/worktrees/maestro-device-core/coresidence-proof/evidence/watch-coresidence.sh &
```

In another, run the flow with routing on and the bundle id set:

```bash
export MAESTRO_DEVICECORE_ASSERT=1
export DEVICECORE_IOS_BUNDLE_ID=dev.mobile.devicecore.conformance.uikit   # belt-and-suspenders; router also sets the sys prop
maestro test prototypes/milestone4/flow.yaml --device 6921573F-D8AB-4AC7-A24C-BC700CD7345D
```

Expected: flow **passes**. Watcher output (`step3`-style) shows `127.0.0.1:8792` LISTEN (device-core) **and** `127.0.0.1:22087` LISTEN (legacy) simultaneously, both runner PIDs (`ConformanceDriverServer-Runner`, `maestro-driver-iosUITests-Runner`), and testmanagerd `Session summary: 2 test sessions`.

- [ ] **Step 5: Prove the verdict came from device-core (not a pass-through).**

Collect all four:
1. During the assert, `lsof -nP -i :8792` shows an **ESTABLISHED** `java` ↔ `127.0.0.1:8792` connection (the CLI JVM's device-core client), and device-core's server log shows a `snapshot` op for bundle `...uikit` at the assert timestamp.
2. Add a one-line log in `DeviceCoreAssertRouter.evaluate` (`logger.info`) emitting the returned `resolution` + `bounds` + verdict; confirm it appears in the run output for the routed step and reports `Resolved(TEXT)` with MEASURED bounds.
3. testmanagerd `2 test sessions` from Step 4's watcher.
4. **Toggle control:** re-run Step 4 with `MAESTRO_DEVICECORE_ASSERT=0`. The flow still passes (legacy verdict) but there is **no** 8792 traffic and no router log line — isolating that the `=1` run was decided by device-core.

- [ ] **Step 6: Negative control — device-core actually decides the fail.**

```bash
export MAESTRO_DEVICECORE_ASSERT=1
maestro test prototypes/milestone4/flow-negative.yaml --device 6921573F-D8AB-4AC7-A24C-BC700CD7345D
```

Expected: flow **fails** at the `assertVisible` step with `AssertionFailure`. device-core's server log shows the `snapshot` op and the router log reports `Resolution.Absent` → verdict false. This is the proof that "green" wasn't a pass-through: device-core returned not-visible and the flow failed exactly where legacy would.

Optional off-screen variant: point `assertVisible` at a text device-core resolves but whose bounds are off-screen (e.g. below the fold before scrolling) → `isVisibleProxy` false → fail, exercising the on-screen half of the proxy.

- [ ] **Step 7: Write up `bringup.md` and commit.**

Record: chosen `<TARGET_TEXT>` + bounds, the exact bring-up commands, the sim UDID/OS actually used, and the six evidence artifacts (positive pass + watcher, lsof ESTABLISHED, router log, toggle control, negative fail). Then:

```bash
git add prototypes/milestone4/
git commit -m "test(m4): bespoke flow, co-resident bring-up runbook, and device-core-decided proof"
```

---

## Milestone-4-specific risks and mitigations

- **JVM/Kotlin/Gradle skew (device-core 21/2.2.20/9.5.1 vs Maestro 17/2.2.0/8.13).** Mitigated by Task 0: publish to mavenLocal (device-core builds with its own Gradle 9.5.1) + force `jvmTarget=17` on the two device-core modules + verify class-file major 61. This is why a composite `includeBuild` is *not* used — it would drag device-core onto Maestro's Gradle 8.13.
- **`getById` is unimplemented on iOS in device-core.** The router declines `id` selectors (`DeviceCoreRouting` returns null), so they stay on legacy. The milestone-4 flow uses a **text** selector. Documented deviation from the spec's "Text or id."
- **One-shot inspect has no retry/timeout.** `evaluateCondition`'s legacy path polls up to `lookupTimeoutMs`; device-core's `inspect()` is a single snapshot. If the element isn't rendered yet, the routed assert fails spuriously. Mitigation: target a static, already-rendered element; if needed, precede the routed `assertVisible` with a legacy step that waits for the screen (a legacy `assertVisible` on a different, always-present element, or a small settle). Called out so a flake isn't misread as a device-core defect.
- **`Resolution.Unavailable` vs `Resolution.Absent`.** Infra failure (socket refused, driver down) must never masquerade as an assertion verdict. The adapter throws `DeviceCoreUnavailable` on `Unavailable`, distinct from `Absent`→fail. If the negative control ever throws `DeviceCoreUnavailable` instead of failing the assert, that's a bring-up problem (8792 down), not a passing negative control.
- **Ambiguous matches.** A `SUBSTRING` match on a non-unique text yields `Resolution.Ambiguous` → treated as not-visible (fail for VISIBLE). Mitigated by `Match.EXACT` + requiring a unique literal, and by the discovery step in Task 6.1.
- **Fixture staging.** device-core's proof staged scenarios via the 8795 control server; a bare `maestro test` won't stage. Task 6.1 handles this by picking an element on the *default* reachable screen (or navigating there with legacy steps) rather than assuming a staged scenario.
- **Bundle-id coupling.** device-core resolves the app-under-test from `devicecore.ios.bundleId`; if it diverges from the flow's `appId`, inspect targets the wrong app. Mitigated by the router setting the system property from `appId` before every `connect()` (Task 3), so it can't drift from the flow.
- **Stale 8792 binding.** A leftover device-core server from a prior run holding 8792 will serve the wrong session. Bring-up (Task 6.2) must confirm a clean 8792 (kill prior `ConformanceDriverServer-Runner`) before starting.
- **Point vs pixel units in the on-screen check.** device-core `Rect` is in points; the check must use `DeviceInfo.widthGrid/heightGrid` (points on iOS), not `widthPixels/heightPixels`. Enforced in Task 4.4 and covered by `AssertVisibleVerdictTest`.
- **Sim/OS availability.** The proof is on iOS 16.4 / iPhone 14 Pro / a specific UDID. The execution environment must have that runtime, or Task 6 must substitute an available iOS-16.4 sim UDID (device-core's iOS text strategy is validated on 16.4).
- **Transient session sufficiency.** Register→serve→release per inspect is enough for a per-command observe (both runners provably live at the serving instant). A continuous device-core session is explicitly out of scope for milestone 4.
