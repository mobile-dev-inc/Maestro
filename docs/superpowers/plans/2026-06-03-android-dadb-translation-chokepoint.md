# Android dadb Translation Chokepoint Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the five scattered per-method `catch`/`DeviceUnreachableException` translations on `fix/android-driver-device-unreachable` with a single transport-error chokepoint, so **every** dadb call in `AndroidDriver` — not just the two data-backed paths — classifies a transport death as a retryable `DeviceUnreachableException`.

**Architecture:** Introduce a `TranslatingDadb` decorator that wraps the injected `Dadb` using Kotlin interface delegation (`Dadb by delegate`) and overrides only the data-plane methods `AndroidDriver` calls (`shell`, `openShell`, `pull`, `install`, `uninstall`), translating any `IOException` out of them into `DeviceUnreachableException`. `AndroidDriver` wraps its constructor `Dadb` once, so all ~25 call sites — including the inline `dadb.shell(...)` calls that bypass the private `shell()` helper — are covered with zero per-site edits. `open()` is intentionally **not** translated (it backs the gRPC socket factory, whose failures surface as `StatusRuntimeException` through gRPC's own path). This collapses the scattered catches and the hand-picked `SocketException`/`SocketTimeoutException` lists into one boundary.

**Tech Stack:** Kotlin, JUnit 5 (Jupiter), Google Truth, MockK. Module: `maestro-client`. Transport: the `dadb` library (`dadb.Dadb`, an interface).

---

## Why this supersedes the scattered approach (context the implementer needs)

The branch currently translates in three places: the private `shell()` helper (`catch (IOException) -> DUE`), `setAllPermissions` (a hand-picked `catch (SocketException)` + `catch (SocketTimeoutException)` list around the APK pull), and `setPermissionInternal` (a `DeviceUnreachableException` rethrow guard). The ~17 **inline** `dadb.shell(...)` calls (e.g. `setOrientation`, `longPress`, `pressKey`, `openLink`) and the non-shell calls (`pull`, `install`, `uninstall`, `openShell`) bypass `shell()` and translate **nothing** — a wedged transport during a swipe or an install still surfaces as a raw `IOException` and misclassifies as TEST_ERROR.

The decorator fixes the problem **at the boundary where it occurs** (the raw dadb call) instead of in N business-logic call sites. `catch (IOException)` is the principled width: total over every transport-death mode (broken pipe, reset, timeout, EOF, protocol error) without enumerating socket subtypes, and narrow enough to let `RuntimeException` logic bugs surface and crash loudly. dadb signals **command** failures via a non-zero `exitCode`, never by throwing — so a thrown `IOException` always means the channel is dead.

**Out of scope (separate sessions):** the dadb-side liveness fixes (write timeout #99, the `MessageQueue.take()` `await()` timeout + signal-on-exception, dadb-internal logging); any maestro-side wall-clock timeout / metrics; and moving `Dadb` construction into a core factory (contract work). This PR is classification only and holds independently of all of them.

**`DeviceUnreachableException`** already exists at `maestro-client/src/main/java/maestro/DeviceUnreachableException.kt`:
```kotlin
class DeviceUnreachableException(val callName: String, cause: Throwable)
    : RuntimeException("Device became unreachable during $callName", cause)
```
It is a `RuntimeException` (deliberately not a `MaestroException`). The worker already maps it to a retryable INFRA_ERROR (`maestro-worker/.../MaestroTestRunner.kt`), so no worker change is needed. The Orchestra propagation fix (commit `9575a133`) stays — it is a different layer and still required.

**No log line at the chokepoint.** Per the repo engineering principles (no boilerplate logging; error messages carry context; only log actual failures needing investigation), the `DeviceUnreachableException(callName, cause)` already carries the op and cause. The real where-it-stalls observability is dadb-internal (separate session). The chokepoint only translates.

---

## File Structure

- **Create** `maestro-client/src/main/java/maestro/drivers/TranslatingDadb.kt`
  - The decorator. One responsibility: translate transport `IOException` -> `DeviceUnreachableException` for the data-plane dadb methods. `internal`, package `maestro.drivers`.
- **Modify** `maestro-client/src/main/java/maestro/drivers/AndroidDriver.kt`
  - Constructor: rename param `dadb` -> `rawDadb`; add `private val dadb: Dadb = TranslatingDadb(rawDadb)` as the **first** body property (must precede `channel` and `androidWebViewHierarchyClient`, which reference `dadb`).
  - `shell()` helper (≈ 1259): drop the now-redundant `catch (IOException) -> DUE` (the decorator does it); keep the `exitCode != 0 -> IOException` check.
  - `setAllPermissions()` (≈ 909): replace the `SocketException`/`SocketTimeoutException` list with a single `catch (DeviceUnreachableException) { throw }` ahead of the best-effort `catch (Exception)`.
  - Remove `import java.net.SocketException` and `import java.net.SocketTimeoutException` (no longer used).
- **Modify** `maestro-cli/src/main/java/maestro/cli/session/MaestroSessionManager.kt:340`
  - Named-arg construction `dadb = Dadb...` -> `rawDadb = Dadb...` (the one named-arg call site).
- **Modify** `maestro-client/src/test/java/maestro/drivers/AndroidDriverTest.kt`
  - Add one test proving an **inline**-shell path (`setOrientation`) surfaces `DeviceUnreachableException`. The existing 9 tests stay and become the behavior-preservation proof (they pass unchanged because the mock becomes the decorator's delegate).

### Testing approach (per repo engineering-principles.md)

- **Assert on observable behavior, not internals.** Every test drives a **public** `AndroidDriver` method (`clearAppState`, `setPermissions`, `setOrientation`) and asserts the exception it throws — never calls `TranslatingDadb` directly. Do **not** add a `TranslatingDadbTest`: don't test the small wrapper and the larger driver that uses it; the driver tests cover the seam with better fidelity ("don't test both the small function and the larger function that wraps it").
- **Mock only the transport boundary.** `mockk<Dadb>(relaxed = true)` stands in for the external device transport (the legitimate equivalent of mocking HTTP); assertions are on real driver behavior, not "the mock was called."
- **Don't over-test the uniform mechanism.** The decorator covers all overridden methods identically; the `setOrientation` (inline shell) test plus the existing helper-shell and APK-pull tests pin the coverage classes. Adding a separate test per dadb method would be boilerplate that drags in gRPC setup for no added fidelity.
- **Self-contained.** Each test inlines its own mock setup and reads top-to-bottom without opening another file.

---

## Task 1: Introduce the decorator and cover the inline-shell call sites

**Files:**
- Test: `maestro-client/src/test/java/maestro/drivers/AndroidDriverTest.kt`
- Create: `maestro-client/src/main/java/maestro/drivers/TranslatingDadb.kt`
- Modify: `maestro-client/src/main/java/maestro/drivers/AndroidDriver.kt`
- Modify: `maestro-cli/src/main/java/maestro/cli/session/MaestroSessionManager.kt:340`

`setOrientation` calls `dadb.shell(...)` directly, bypassing the private `shell()` helper. On the current branch a transport throw there is **not** translated, so this test fails until the decorator exists — proving the decorator (not the helper) is what classifies, and therefore that every inline call site is covered.

- [ ] **Step 1: Write the failing test**

Add to `maestro-client/src/test/java/maestro/drivers/AndroidDriverTest.kt` (and add `import maestro.device.DeviceOrientation` to the imports):

```kotlin
    @Test
    fun `setOrientation surfaces a dadb transport failure as DeviceUnreachableException`() {
        // setOrientation calls dadb.shell(...) directly, bypassing the private shell() helper.
        // Proves the TranslatingDadb decorator — not the helper — is what classifies transport
        // death, so every inline dadb call site is covered, not just helper-routed ones.
        val dadb = mockk<Dadb>(relaxed = true)
        every { dadb.shell(any()) } throws SocketException("Broken pipe")

        val driver = AndroidDriver(dadb)

        assertThrows<DeviceUnreachableException> { driver.setOrientation(DeviceOrientation.PORTRAIT) }
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :maestro-client:test --tests "maestro.drivers.AndroidDriverTest"`
Expected: FAIL — `setOrientation` calls `dadb.shell(...)` inline (no translation on the current branch), so `SocketException` propagates raw; `assertThrows<DeviceUnreachableException>` fails with an unexpected exception type. (The other 9 tests pass.)

- [ ] **Step 3: Create the decorator**

Create `maestro-client/src/main/java/maestro/drivers/TranslatingDadb.kt`:

```kotlin
package maestro.drivers

import dadb.AdbShellResponse
import dadb.AdbShellStream
import dadb.Dadb
import maestro.DeviceUnreachableException
import java.io.File
import java.io.IOException

/**
 * Wraps a [Dadb] so that any [IOException] out of a transport call surfaces as a
 * [DeviceUnreachableException] (infra, retryable) instead of a bare IOException that upstream
 * misclassifies as a customer-facing test failure.
 *
 * dadb signals command failures via a non-zero exitCode, never by throwing — so a thrown
 * IOException always means the channel itself is dead (broken pipe, reset, timeout, EOF, protocol
 * error). IOException is therefore the correct width: total over every transport-death mode without
 * enumerating socket subtypes, and narrow enough to let RuntimeException logic bugs surface.
 *
 * Only the data-plane methods AndroidDriver calls are overridden; everything else delegates raw via
 * `Dadb by delegate`. open() is intentionally NOT translated — it backs the gRPC socket factory,
 * whose failures surface as StatusRuntimeException through gRPC's own error path.
 */
internal class TranslatingDadb(private val delegate: Dadb) : Dadb by delegate {

    override fun shell(command: String): AdbShellResponse =
        runDadbCall("shell: $command") { delegate.shell(command) }

    override fun openShell(command: String): AdbShellStream =
        runDadbCall("openShell: $command") { delegate.openShell(command) }

    override fun pull(dst: File, remotePath: String) =
        runDadbCall("pull: $remotePath") { delegate.pull(dst, remotePath) }

    override fun install(file: File, vararg options: String) =
        runDadbCall("install: ${file.name}") { delegate.install(file, *options) }

    override fun uninstall(packageName: String) =
        runDadbCall("uninstall: $packageName") { delegate.uninstall(packageName) }

    private inline fun <T> runDadbCall(callName: String, block: () -> T): T =
        try {
            block()
        } catch (e: IOException) {
            throw DeviceUnreachableException(callName, e)
        }
}
```

- [ ] **Step 4: Wire the decorator into `AndroidDriver`**

In `maestro-client/src/main/java/maestro/drivers/AndroidDriver.kt`:

(a) Change the constructor param from `private val dadb: Dadb` to a plain `rawDadb: Dadb`:

```kotlin
class AndroidDriver(
    rawDadb: Dadb,
    hostPort: Int? = null,
    private var emulatorName: String = "",
    private val reinstallDriver: Boolean = true,
    private val metricsProvider: Metrics = MetricsProvider.getInstance(),
    ) : Driver {

    // Every dadb call in this driver goes through the decorator so a transport failure classifies
    // as DeviceUnreachableException (infra) instead of a bare IOException (misclassified as a test
    // failure). Declared first: `channel` and `androidWebViewHierarchyClient` below reference it.
    private val dadb: Dadb = TranslatingDadb(rawDadb)

    private var open = false
```

(Leave `open`, `hostPort`, `metrics`, `channel`, `androidWebViewHierarchyClient`, etc. as they are — they reference `dadb`, which now resolves to the decorated property.)

(b) Simplify the private `shell()` helper (≈ line 1259) — drop the redundant transport catch:

```kotlin
    private fun shell(command: String): String {
        // Transport failures are already translated to DeviceUnreachableException by TranslatingDadb.
        // A non-zero exitCode means the device answered and the command failed — a test-domain
        // signal that stays a plain IOException.
        val response = dadb.shell(command)
        if (response.exitCode != 0) {
            throw IOException("$command: ${response.allOutput}")
        }
        return response.output
    }
```

- [ ] **Step 5: Update the one named-arg construction site**

In `maestro-cli/src/main/java/maestro/cli/session/MaestroSessionManager.kt` (≈ line 340), rename the named argument:

```kotlin
        val driver = AndroidDriver(
            rawDadb = Dadb
                .list()
                .find { it.toString() == instanceId }
                ?: Dadb.discover()
                ?: error("Unable to find device with id $instanceId"),
            hostPort = driverHostPort,
            emulatorName = instanceId,
            reinstallDriver = reinstallDriver,
        )
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew :maestro-client:test --tests "maestro.drivers.AndroidDriverTest"`
Expected: PASS (all 10 tests — the new `setOrientation` test and the existing 9, which pass unchanged because the mock is now the decorator's delegate).

- [ ] **Step 7: Commit**

```bash
git add maestro-client/src/main/java/maestro/drivers/TranslatingDadb.kt \
        maestro-client/src/main/java/maestro/drivers/AndroidDriver.kt \
        maestro-cli/src/main/java/maestro/cli/session/MaestroSessionManager.kt \
        maestro-client/src/test/java/maestro/drivers/AndroidDriverTest.kt
git commit -m "fix(android-driver): translate every dadb transport failure at one chokepoint (TranslatingDadb)"
```

---

## Task 2: Collapse the `setAllPermissions` hand-picked Socket list

**Files:**
- Modify: `maestro-client/src/main/java/maestro/drivers/AndroidDriver.kt` (`setAllPermissions()` ≈ 909; imports ≈ 56-57)

With the decorator translating the APK pull's transport throw, the `SocketException`/`SocketTimeoutException` list is dead — `AndroidAppFiles.getApkFile(dadb, appId)` now throws `DeviceUnreachableException` directly (its `dadb.shell`/`dadb.pull` go through the decorated `dadb`). We must keep that exception from being swallowed by the best-effort `catch (Exception)` (it parses the APK), so we rethrow it explicitly first.

- [ ] **Step 1: Confirm the existing APK-pull tests are present and green**

The two relevant tests already exist in `AndroidDriverTest.kt` and must stay green through this refactor:
- `setPermissions all surfaces an APK-pull transport timeout as DeviceUnreachableException`
- `setPermissions all surfaces an APK-pull broken pipe as DeviceUnreachableException`

Run: `./gradlew :maestro-client:test --tests "maestro.drivers.AndroidDriverTest"`
Expected: PASS (they currently pass via the Socket list; after Step 2 they must still pass via the decorator + rethrow).

- [ ] **Step 2: Replace the Socket list with a `DeviceUnreachableException` rethrow guard**

Replace `setAllPermissions()` (≈ line 909):

```kotlin
    private fun setAllPermissions(appId: String, permissionValue: String) {
        val permissions = try {
            val apkFile = AndroidAppFiles.getApkFile(dadb, appId)
            val parsed = ApkFile(apkFile).apkMeta.usesPermissions
            apkFile.delete()
            parsed
        } catch (unreachable: DeviceUnreachableException) {
            // The APK pull hit a wedged transport (already translated by TranslatingDadb). Surface as
            // infra instead of silently skipping the grant and letting the app launch with no
            // permissions. Must precede the best-effort catch below, which would otherwise swallow it.
            throw unreachable
        } catch (e: Exception) {
            // Best-effort: if we can't read/parse the APK for any non-transport reason, skip granting.
            logger.debug("Failed to read APK permissions for $appId: ${e.message}")
            null
        }
        permissions?.forEach { permission ->
            setPermissionInternal(appId, permission, permissionValue)
        }
    }
```

Then remove the two now-unused imports (≈ lines 56-57):

```kotlin
import java.net.SocketException
import java.net.SocketTimeoutException
```

(Keep `import java.io.IOException` — `shell()` still throws it on a non-zero exit. Verify with `rg -n "SocketException|SocketTimeoutException" maestro-client/src/main/java/maestro/drivers/AndroidDriver.kt` returning nothing before deleting the imports.)

`setPermissionInternal`'s existing `catch (DeviceUnreachableException) { throw unreachable }` guard is unchanged — still required, since `shell()` can throw `DeviceUnreachableException` via the decorator.

- [ ] **Step 3: Run tests to verify they pass**

Run: `./gradlew :maestro-client:test --tests "maestro.drivers.AndroidDriverTest"`
Expected: PASS (all 10 tests; the two APK-pull tests now prove the decorator + rethrow path, not the deleted Socket list).

- [ ] **Step 4: Commit**

```bash
git add maestro-client/src/main/java/maestro/drivers/AndroidDriver.kt
git commit -m "fix(android-driver): drop hand-picked Socket exception list, rely on TranslatingDadb"
```

---

## Task 3: Full-module verification and PR update

**Files:** none (verification + PR).

- [ ] **Step 1: Run the full `maestro-client` suite**

Run: `./gradlew :maestro-client:test`
Expected: BUILD SUCCESSFUL — `AndroidDriverTest` (10), `AndroidDriverRetryConfigTest`, `IOSDriverTest`, `WebDriverTest` all green. If an unrelated test was already red on the base branch, note it explicitly; do not silence it.

- [ ] **Step 2: Confirm the CLI module still compiles (named-arg rename)**

Run: `./gradlew :maestro-cli:compileKotlin`
Expected: BUILD SUCCESSFUL (the `rawDadb =` rename in `MaestroSessionManager.kt` is the only CLI-side change).

- [ ] **Step 3: Sanity-check the net diff vs main**

Run: `git diff main --stat`
Expected files: `TranslatingDadb.kt` (new), `AndroidDriver.kt` (constructor + `shell()` + `setAllPermissions` + two import removals), `MaestroSessionManager.kt` (one named arg), `AndroidDriverTest.kt` (one new test). The net diff should read as the chokepoint story even though the branch's earlier scattered commits remain in history.

Run: `rg -n "catch \(e: SocketException\)|catch \(e: SocketTimeoutException\)|catch \(e: IOException\)" maestro-client/src/main/java/maestro/drivers/AndroidDriver.kt`
Expected: no transport-translation catches remain in `AndroidDriver` (the only `IOException` reference left is the `throw IOException(...)` on non-zero exit in `shell()`).

- [ ] **Step 4: Update the PR**

Push to the existing `fix/android-driver-device-unreachable` branch (this is PR #3331 — **do not rewrite published history**; layer these commits on top). Update the PR description to reflect the superseding approach:
- Problem: a wedged adbd makes dadb calls throw, but only `shell()` + the `setPermissions` APK pull translated it — the ~17 inline `dadb.shell(...)` sites and `pull`/`install`/`uninstall`/`openShell` surfaced raw `IOException` -> misclassified as TEST_ERROR.
- Fix: one `TranslatingDadb` decorator translates every transport `IOException` -> `DeviceUnreachableException` at the dadb boundary, covering all call sites; the scattered catches and the hand-picked `Socket*` list are removed. `open()` (gRPC socket factory) is intentionally left raw. Worker already classifies `DeviceUnreachableException` as retryable INFRA_ERROR (no worker change).
- Scope/non-goals: classification only. dadb-side liveness (write timeout #99, `MessageQueue.take()` `await()` timeout + signal-on-exception, dadb-internal logging), any maestro-side timeout/metrics, and the core dadb factory/ownership move are explicitly separate.

---

## Self-Review

**Spec coverage:**
- "One chokepoint translating every transport throw, all ~25 sites" -> Task 1 (`TranslatingDadb` + constructor wiring). ✅
- "Cover inline `dadb.shell` sites that bypass `shell()`" -> Task 1 `setOrientation` test + decorator. ✅
- "Remove hand-picked Socket lists / scattered catches" -> Task 1 (`shell()` catch) + Task 2 (`setAllPermissions` list + imports). ✅
- "Keep best-effort APK parse, don't swallow DUE" -> Task 2 (`catch (DeviceUnreachableException) { throw }` before generic). ✅
- "Keep `setPermissionInternal` DUE rethrow + Orchestra fix" -> unchanged, noted in context. ✅
- "`open()` not translated (gRPC carve-out)" -> decorator omits `open`; documented. ✅
- "No log line at chokepoint" -> decorator only translates; documented with the principle. ✅

**Placeholder scan:** No TBD/TODO/"handle appropriately" — every step has exact code, exact file paths, exact commands and expected output. ✅

**Type consistency:** `DeviceUnreachableException(callName: String, cause: Throwable)` used consistently; decorator overrides match the `Dadb` interface signatures (`shell(String): AdbShellResponse`, `openShell(String): AdbShellStream`, `pull(File, String)`, `install(File, vararg String)`, `uninstall(String)`); constructor property `dadb: Dadb` (decorated) referenced by `channel`/`androidWebViewHierarchyClient`; `rawDadb` is the plain param. ✅

**Ordering risk:** `private val dadb = TranslatingDadb(rawDadb)` must be declared before `channel`/`androidWebViewHierarchyClient` (they reference `dadb`); Step 4(a) places it first in the body. Task 2 depends on Task 1's decorator (so `getApkFile` throws DUE); tasks are sequential and committed in order. ✅
