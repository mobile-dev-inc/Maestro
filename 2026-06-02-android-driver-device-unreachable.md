# AndroidDriver DeviceUnreachableException Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `AndroidDriver` translate a wedged-adbd transport timeout into `DeviceUnreachableException` (and stop `setPermissions` from swallowing it), so it classifies as a retryable INFRA_ERROR instead of a customer-facing TEST_ERROR.

**Architecture:** Mirror the iOS fix in [mobile-dev-inc/Maestro#3212](https://github.com/mobile-dev-inc/Maestro/pull/3212), which introduced `maestro.DeviceUnreachableException` (a plain `RuntimeException`, deliberately *not* a `MaestroException`) and translated transport `SocketTimeoutException`s into it at the driver boundary. The iOS `IOSDriver` already does this; `AndroidDriver` is the only driver that doesn't. We add the same translation at Android's dadb boundary (the private `shell()` helper and the `setPermissions` APK-pull), and make `setPermissions`' two swallow sites let `DeviceUnreachableException` propagate.

**Tech Stack:** Kotlin, JUnit 5 (Jupiter), Google Truth, MockK. The driver-under-test talks to the device through the `dadb` library (`dadb.Dadb`).

---

## Context the implementer needs

**Where to work:** This change lands in the **`mobile-dev-inc/Maestro`** repo (module `maestro-client`). The copilot monorepo vendors it as a git submodule at `/Users/stevieclifton/codes/copilot/maestro` — you may implement either in a fresh clone of `mobile-dev-inc/Maestro` or directly in that submodule checkout. All file paths below are relative to the **maestro repo root** (e.g. `maestro-client/src/...`).

**The bug being fixed.** When the adb connection to a device wedges, a dadb call throws `java.net.SocketTimeoutException` (read SO_TIMEOUT, or — once dadb PR #99 lands — a write timeout). On the Android path that error is mishandled two ways:
1. `AndroidDriver.shell()` rewraps it as a generic `IOException`. Upstream, Orchestra turns that into `MaestroException.UnableToSetPermissions` / `UnableToClearState`, which the worker classifies as `TestFailure` → **TEST_ERROR** (non-retryable, customer-facing) — the wrong bucket for an infra failure.
2. `setPermissions` actively **swallows** transport errors in two places: `setAllPermissions` wraps the APK pull in `runCatching {}` (silently skips granting on failure), and `setPermissionInternal` has a `catch (Exception)` that logs at `debug` and continues the loop. So even a correctly-typed error gets eaten before it can be classified.

**Why `DeviceUnreachableException` fixes it.** The worker **already** has a dedicated branch for it (`maestro-worker/.../MaestroTestRunner.kt:184`) that maps it to `TestExecutionException.Unexpected` → **INFRA_ERROR** (retryable). So once `AndroidDriver` throws `DeviceUnreachableException` and `setPermissions` stops swallowing it, the end-to-end classification is correct with **no worker change required**.

**Relationship to dadb PR #99.** Independent. PR #99 (the dadb write timeout) is what makes a wedged *write* actually throw `SocketTimeoutException` fast in production. This maestro change makes that throw — and the read-timeout throw that already exists — classify correctly. The tests below mock `dadb` throwing `SocketTimeoutException`, so this work is fully testable on its own without PR #99 merged.

**`DeviceUnreachableException` already exists** at `maestro-client/src/main/java/maestro/DeviceUnreachableException.kt`:
```kotlin
class DeviceUnreachableException(
    val callName: String,
    cause: Throwable,
) : RuntimeException("Device became unreachable during $callName", cause)
```
It is in package `maestro`, and `AndroidDriver.kt` already imports `maestro.*` (line 32), so **no new import is needed for it**. You only add `import java.net.SocketTimeoutException`.

**Scope / non-goals.** Translate only the dadb calls on the two paths the production data implicates — the private `shell()` chokepoint (covers `clearAppState` and every `pm grant`/`pm clear`/`am`/`settings` op) and the `setPermissions` APK pull. Do **not** wrap the other direct dadb calls (`addMedia` push, app `install`, the gRPC `dadb.open(...)` socket factory, the `openShell(...)` instrumentation session) in this PR — they aren't in the timeout data, and keeping the change small keeps it reviewable. Note this as a possible follow-up in the PR description.

---

## File Structure

- **Modify** `maestro-client/src/main/java/maestro/drivers/AndroidDriver.kt`
  - `shell()` (≈ line 1244): translate `SocketTimeoutException` → `DeviceUnreachableException` (ordered before the existing `IOException` catch).
  - `setPermissionInternal()` (≈ line 926): rethrow `DeviceUnreachableException` before the generic swallow.
  - `setAllPermissions()` (≈ line 906): replace `runCatching {}` so a transport timeout on the APK pull surfaces as `DeviceUnreachableException`, while non-transport (APK-parse) failures stay best-effort.
  - Add `import java.net.SocketTimeoutException`.
- **Create** `maestro-client/src/test/java/maestro/drivers/AndroidDriverTest.kt`
  - Unit tests mocking `dadb.Dadb` (MockK), mirroring the existing `IOSDriverTest.kt` in the same directory. MockK is already on the `maestro-client` test classpath (`testImplementation(libs.mockk)`).

---

## Task 1: Translate transport timeouts in `shell()`

**Files:**
- Create: `maestro-client/src/test/java/maestro/drivers/AndroidDriverTest.kt`
- Modify: `maestro-client/src/main/java/maestro/drivers/AndroidDriver.kt` (`shell()` ≈ 1244, imports ≈ 55)

The private `shell()` is the chokepoint for almost every AndroidDriver operation (including `clearAppState`, which calls it via `isPackageInstalled` and `pm clear`). Translating here fixes the ~8% "clear state" cases and every other `shell`-routed op at once.

- [ ] **Step 1: Write the failing test**

Create `maestro-client/src/test/java/maestro/drivers/AndroidDriverTest.kt`:

```kotlin
package maestro.drivers

import com.google.common.truth.Truth.assertThat
import dadb.Dadb
import io.mockk.every
import io.mockk.mockk
import maestro.DeviceUnreachableException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.SocketTimeoutException

class AndroidDriverTest {

    @Test
    fun `clearAppState surfaces a dadb transport timeout as DeviceUnreachableException`() {
        val dadb = mockk<Dadb>(relaxed = true)
        every { dadb.shell(any()) } throws SocketTimeoutException("timeout")

        val driver = AndroidDriver(dadb)

        val thrown = assertThrows<DeviceUnreachableException> { driver.clearAppState("com.example.app") }
        assertThat(thrown.cause).isInstanceOf(SocketTimeoutException::class.java)
    }
}
```

Note: `clearAppState` first calls `isPackageInstalled`, which calls `shell("pm list packages ...")` → `dadb.shell(...)`. The mock throws there, so we never reach `pm clear`; the timeout still originates in a real `shell()` call. The `relaxed = true` mock lets `AndroidDriver` construct without a real device (the gRPC channel and webview client are built lazily and never make a call in this test).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :maestro-client:test --tests "maestro.drivers.AndroidDriverTest"`
Expected: FAIL — before the fix `shell()` rewraps `SocketTimeoutException` as a plain `IOException`, so `isPackageInstalled` rethrows an `IOException` (not `DeviceUnreachableException`); `assertThrows<DeviceUnreachableException>` fails with an unexpected exception type.

- [ ] **Step 3: Add the import**

In `maestro-client/src/main/java/maestro/drivers/AndroidDriver.kt`, add to the import block (next to `import java.io.IOException`):

```kotlin
import java.net.SocketTimeoutException
```

(Do **not** add an import for `DeviceUnreachableException` — it is already covered by the existing `import maestro.*`.)

- [ ] **Step 4: Translate in `shell()`**

Replace the existing `shell()` (≈ line 1244):

```kotlin
    private fun shell(command: String): String {
        val response: AdbShellResponse = try {
            dadb.shell(command)
        } catch (e: IOException) {
            throw IOException(command, e)
        }

        if (response.exitCode != 0) {
            throw IOException("$command: ${response.allOutput}")
        }
        return response.output
    }
```

with:

```kotlin
    private fun shell(command: String): String {
        val response: AdbShellResponse = try {
            dadb.shell(command)
        } catch (e: SocketTimeoutException) {
            // adbd stopped servicing the socket (read/write/connect timeout). This is the device
            // transport dying, not a test failure — surface it as infra so the job retries.
            // SocketTimeoutException is an IOException, so this catch MUST come first.
            throw DeviceUnreachableException("shell: $command", e)
        } catch (e: IOException) {
            throw IOException(command, e)
        }

        if (response.exitCode != 0) {
            throw IOException("$command: ${response.allOutput}")
        }
        return response.output
    }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :maestro-client:test --tests "maestro.drivers.AndroidDriverTest"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add maestro-client/src/main/java/maestro/drivers/AndroidDriver.kt \
        maestro-client/src/test/java/maestro/drivers/AndroidDriverTest.kt
git commit -m "fix(android-driver): translate dadb socket timeout to DeviceUnreachableException in shell()"
```

---

## Task 2: Stop `setPermissionInternal` swallowing the transport failure

**Files:**
- Modify: `maestro-client/src/main/java/maestro/drivers/AndroidDriver.kt` (`setPermissionInternal()` ≈ 926)
- Test: `maestro-client/src/test/java/maestro/drivers/AndroidDriverTest.kt`

`setPermissions` (the ~92% case) loops over permissions calling `setPermissionInternal`, whose `catch (Exception)` swallows everything that isn't a "not a changeable permission" message — including a `DeviceUnreachableException`. We rethrow the transport failure while keeping the legitimate best-effort swallow.

- [ ] **Step 1: Write the failing test (and a regression guard)**

Add these two methods to `AndroidDriverTest` (and add the `dadb.AdbShellResponse` import to the file's imports):

```kotlin
    @Test
    fun `setPermissions surfaces a dadb transport timeout as DeviceUnreachableException`() {
        val dadb = mockk<Dadb>(relaxed = true)
        every { dadb.shell(any()) } throws SocketTimeoutException("timeout")

        val driver = AndroidDriver(dadb)

        assertThrows<DeviceUnreachableException> {
            driver.setPermissions("com.example.app", mapOf("camera" to "allow"))
        }
    }

    @Test
    fun `setPermissions still swallows a non-changeable permission error`() {
        val dadb = mockk<Dadb>(relaxed = true)
        every { dadb.shell(any()) } returns AdbShellResponse(
            output = "android.permission.INTERNET is not a changeable permission type",
            errorOutput = "",
            exitCode = 255,
        )

        val driver = AndroidDriver(dadb)

        // Best-effort path: a non-transport grant failure must NOT throw.
        driver.setPermissions("com.example.app", mapOf("camera" to "allow"))
    }
```

Add this import to the top of the test file:

```kotlin
import dadb.AdbShellResponse
```

Note: `mapOf("camera" to "allow")` resolves (via `translatePermissionName`) to exactly one permission, `android.permission.CAMERA`, which is not in `appOpsPermissions`, so it takes the `shell("pm grant ...")` branch — exactly one `dadb.shell` call. The second test returns a non-zero exit whose output contains "is not a changeable permission type", which `shell()` turns into a plain `IOException` that `setPermissionInternal` is meant to swallow.

- [ ] **Step 2: Run tests to verify the new transport test fails**

Run: `./gradlew :maestro-client:test --tests "maestro.drivers.AndroidDriverTest"`
Expected: `setPermissions surfaces a dadb transport timeout ...` FAILS (shell now throws `DeviceUnreachableException`, but `setPermissionInternal`'s `catch (Exception)` swallows it, so `setPermissions` returns normally and no exception is thrown). The `still swallows ...` test should already PASS.

- [ ] **Step 3: Rethrow `DeviceUnreachableException` in `setPermissionInternal`**

Replace `setPermissionInternal()` (≈ line 926):

```kotlin
    private fun setPermissionInternal(appId: String, permission: String, rawValue: String) {
        try {
            if (permission in appOpsPermissions) {
                setAppOp(appId, permission, rawValue)
            } else {
                shell("pm ${translatePermissionValue(rawValue)} $appId $permission")
            }
        } catch (exception: Exception) {
            // Ignore if it's something that the user doesn't have control over (e.g. you can't grant / deny INTERNET)
            if (exception.message?.contains("is not a changeable permission type") == false) {
                // Debug level is fine.
                // We don't need to be loud about this. IOExceptions were already caught in shell(..)
                // Remaining issues are likely due to "all" containing permissions that the app doesn't support.
                logger.debug("Failed to set permission $permission for app $appId: ${exception.message}")
            }
        }
    }
```

with (note the new `catch (DeviceUnreachableException)` placed **before** the generic catch):

```kotlin
    private fun setPermissionInternal(appId: String, permission: String, rawValue: String) {
        try {
            if (permission in appOpsPermissions) {
                setAppOp(appId, permission, rawValue)
            } else {
                shell("pm ${translatePermissionValue(rawValue)} $appId $permission")
            }
        } catch (unreachable: DeviceUnreachableException) {
            // The device transport is wedged. Propagate as infra instead of swallowing and looping
            // onto the next permission against a dead connection.
            throw unreachable
        } catch (exception: Exception) {
            // Ignore if it's something that the user doesn't have control over (e.g. you can't grant / deny INTERNET)
            if (exception.message?.contains("is not a changeable permission type") == false) {
                // Debug level is fine.
                // We don't need to be loud about this. IOExceptions were already caught in shell(..)
                // Remaining issues are likely due to "all" containing permissions that the app doesn't support.
                logger.debug("Failed to set permission $permission for app $appId: ${exception.message}")
            }
        }
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :maestro-client:test --tests "maestro.drivers.AndroidDriverTest"`
Expected: PASS (all four tests).

- [ ] **Step 5: Commit**

```bash
git add maestro-client/src/main/java/maestro/drivers/AndroidDriver.kt \
        maestro-client/src/test/java/maestro/drivers/AndroidDriverTest.kt
git commit -m "fix(android-driver): propagate DeviceUnreachableException from setPermissionInternal instead of swallowing"
```

---

## Task 3: Stop `setAllPermissions` swallowing the APK-pull transport failure

**Files:**
- Modify: `maestro-client/src/main/java/maestro/drivers/AndroidDriver.kt` (`setAllPermissions()` ≈ 906)
- Test: `maestro-client/src/test/java/maestro/drivers/AndroidDriverTest.kt`

The default launchApp auto-grant uses the `"all"` key, which routes through `setAllPermissions`. That method pulls the APK (`AndroidAppFiles.getApkFile`, which calls `dadb.shell(...)` and `dadb.pull(...)` directly) inside a `runCatching {}` — so a transport timeout there is silently swallowed and the app launches with no permissions granted. We surface the transport timeout as `DeviceUnreachableException` while keeping the best-effort behaviour for genuine APK-read/parse failures.

- [ ] **Step 1: Write the failing test**

Add to `AndroidDriverTest`:

```kotlin
    @Test
    fun `setPermissions all surfaces an APK-pull transport timeout as DeviceUnreachableException`() {
        val dadb = mockk<Dadb>(relaxed = true)
        // setAllPermissions -> AndroidAppFiles.getApkFile -> dadb.shell("pm list packages -f ...")
        every { dadb.shell(any()) } throws SocketTimeoutException("timeout")

        val driver = AndroidDriver(dadb)

        assertThrows<DeviceUnreachableException> {
            driver.setPermissions("com.example.app", mapOf("all" to "allow"))
        }
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :maestro-client:test --tests "maestro.drivers.AndroidDriverTest"`
Expected: the new `setPermissions all ...` test FAILS — `runCatching {}` swallows the `SocketTimeoutException`, `isSuccess` is false, the grant block is skipped, and `setPermissions` returns normally (no exception).

- [ ] **Step 3: Replace `runCatching` in `setAllPermissions`**

Replace `setAllPermissions()` (≈ line 906):

```kotlin
    private fun setAllPermissions(appId: String, permissionValue: String) {
        val permissionsResult = runCatching {
            val apkFile = AndroidAppFiles.getApkFile(dadb, appId)
            val permissions = ApkFile(apkFile).apkMeta.usesPermissions
            apkFile.delete()
            permissions
        }
        if (permissionsResult.isSuccess) {
            permissionsResult.getOrNull()?.let {
                it.forEach { permission ->
                    setPermissionInternal(appId, permission, permissionValue)
                }
            }
        }
    }
```

with:

```kotlin
    private fun setAllPermissions(appId: String, permissionValue: String) {
        val permissions = try {
            val apkFile = AndroidAppFiles.getApkFile(dadb, appId)
            val parsed = ApkFile(apkFile).apkMeta.usesPermissions
            apkFile.delete()
            parsed
        } catch (e: SocketTimeoutException) {
            // The APK pull hit a wedged transport. Surface as infra instead of silently skipping
            // the grant and letting the app launch without its permissions.
            throw DeviceUnreachableException("setPermissions: read APK for $appId", e)
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

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :maestro-client:test --tests "maestro.drivers.AndroidDriverTest"`
Expected: PASS (all five tests).

- [ ] **Step 5: Commit**

```bash
git add maestro-client/src/main/java/maestro/drivers/AndroidDriver.kt \
        maestro-client/src/test/java/maestro/drivers/AndroidDriverTest.kt
git commit -m "fix(android-driver): surface APK-pull transport timeout as DeviceUnreachableException in setAllPermissions"
```

---

## Task 4: Verify the whole module and open the PR

**Files:** none (verification + PR).

- [ ] **Step 1: Run the full maestro-client test suite**

Run: `./gradlew :maestro-client:test`
Expected: BUILD SUCCESSFUL, including the new `AndroidDriverTest` and the existing `IOSDriverTest` / `WebDriverTest`. If an unrelated test was already failing on the base branch, note it explicitly — do not silence it.

- [ ] **Step 2: Sanity-check the diff**

Run: `git diff main --stat`
Expected: only `maestro-client/src/main/java/maestro/drivers/AndroidDriver.kt` (one import + three method bodies) and the new `maestro-client/src/test/java/maestro/drivers/AndroidDriverTest.kt`. No other files.

- [ ] **Step 3: Open the PR**

Push the branch and open a PR against `mobile-dev-inc/Maestro` `main`. PR body should cover:
- Problem: a wedged adbd makes Android driver calls fail, but the error becomes a generic `IOException` → `UnableToSetPermissions`/`UnableToClearState` (`MaestroException`) → classified as TEST_ERROR; and `setPermissions` swallows it in two places.
- Fix: translate `SocketTimeoutException` → `DeviceUnreachableException` at the dadb boundary (`shell()` + the `setPermissions` APK pull) and stop `setPermissions` swallowing it — mirroring the iOS treatment in #3212. The worker already classifies `DeviceUnreachableException` as INFRA_ERROR/retryable, so no worker change is needed.
- Scope/non-goals: only `shell()`-routed ops and the `setPermissions` APK pull are translated (covers the documented 92% setPermissions + 8% clearState). Other direct dadb ops (`addMedia`, `install`, gRPC `open`, `openShell`) are a possible follow-up.
- Relationship to dadb write-timeout (PR #99): independent and complementary — that PR makes wedged *writes* throw quickly; this PR makes the resulting throw classify correctly.

---

## Self-Review

**Spec coverage:**
- "Translate transport timeout → DeviceUnreachableException at the AndroidDriver boundary" → Task 1 (`shell()`) + Task 3 (APK pull). ✅
- "Stop setPermissions swallowing it" → Task 2 (`setPermissionInternal`) + Task 3 (`setAllPermissions`). ✅
- "clearAppState classifies correctly" → covered for free by Task 1 (it routes through `shell()`); pinned by the Task 1 test. ✅
- "Keep the legitimate best-effort swallow" → Task 2 regression-guard test + Task 3's non-transport `catch (Exception)` branch. ✅
- "No worker change needed" → documented; relies on existing `MaestroTestRunner.kt:184`. ✅

**Placeholder scan:** No TBD/TODO/"handle errors appropriately" — every step has exact code and exact commands. ✅

**Type consistency:** `DeviceUnreachableException(callName: String, cause: Throwable)` used consistently; `AdbShellResponse(output, errorOutput, exitCode)` matches the dadb constructor; `mockk<Dadb>(relaxed = true)` / `every { dadb.shell(any()) }` consistent across all tests; `SocketTimeoutException` caught before `IOException` everywhere it appears (load-bearing, since the former is a subtype of the latter). ✅

**Ordering risk:** Task 2's transport test depends on Task 1's `shell()` change (so `shell()` throws `DeviceUnreachableException`); tasks are sequential and committed in order, so this holds. ✅
