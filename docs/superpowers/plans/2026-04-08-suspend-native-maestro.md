# Suspend-Native Maestro Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `Maestro` methods natively `suspend`, replacing `MaestroTimer` polling loops with coroutine-native timeout/delay primitives so that coroutine cancellation works end-to-end without needing a separate wrapper class.

**Architecture:** `Maestro` becomes the suspend boundary — each method wraps its blocking `Driver` calls with `runInterruptible(Dispatchers.IO)`. Polling loops in `Maestro` (e.g. `findElementWithTimeout`, `waitUntilVisible`) use `kotlinx.coroutines.withTimeoutOrNull` and `delay()` instead of `MaestroTimer.withTimeout` + `Thread.sleep`. Orchestra already takes `Maestro` and already has suspend methods — it just works. Non-suspend callers (CLI tools, Studio) add `runBlocking` at the boundary.

**Branch base:** `6ad27ba7` (PR1: Orchestra cancellation fixes). No `SuspendingMaestro` exists on this branch. Orchestra already takes `Maestro` directly.

**Tech Stack:** Kotlin coroutines (`runInterruptible`, `Dispatchers.IO`, `withTimeoutOrNull`, `delay`), existing test infrastructure

---

### File Structure

| File | Responsibility | Action |
|------|---------------|--------|
| `maestro-client/.../maestro/Maestro.kt` | High-level device operations | Modify: all public methods become `suspend fun`, wrap driver calls in `runInterruptible(Dispatchers.IO)`, convert polling loops to coroutine-native |
| `maestro-orchestra/.../orchestra/Orchestra.kt` | Command dispatch (already suspend) | Modify: only the `withTimeoutSuspend` call if convertible |
| `maestro-orchestra/.../orchestra/geo/Traveller.kt` | GPS travel simulation | Modify: `travel()` becomes `suspend`, `Thread.sleep` → `delay` |
| `maestro-cli/.../cli/util/ScreenshotUtils.kt` | Debug screenshot capture (non-suspend) | Modify: wrap `maestro.takeScreenshot` in `runBlocking` |
| `maestro-cli/.../cli/runner/TestRunner.kt` | Flow execution error handler | Modify: wrap `maestro.isShutDown()` in `runBlocking` |
| `maestro-cli/.../cli/command/RecordCommand.kt` | Record command | Modify: wrap `maestro.startScreenRecording` in `runBlocking` |
| `maestro-cli/.../cli/command/StudioCommand.kt` | Studio command | Modify: wrap `maestro.setAndroidChromeDevToolsEnabled` in `runBlocking` |
| `maestro-cli/.../cli/command/PrintHierarchyCommand.kt` | Hierarchy command | Modify: wrap calls in `runBlocking` |
| `maestro-studio/.../studio/DeviceService.kt` | Studio HTTP routes | Modify: wrap direct `maestro.*` calls in `runBlocking` |
| `maestro-cli/.../cli/mcp/tools/TakeScreenshotTool.kt` | MCP screenshot tool | Modify: wrap in `runBlocking` |
| `maestro-cli/.../cli/mcp/tools/InspectViewHierarchyTool.kt` | MCP hierarchy tool | Modify: wrap in `runBlocking` |
| `maestro-test/.../test/IntegrationTest.kt` | Integration tests | No changes needed — tests already run in `runBlocking` |

---

### Task 1: Make Maestro methods suspend with runInterruptible(Dispatchers.IO)

Every public method on `Maestro` that calls `driver.*` becomes `suspend fun` and wraps its body in `runInterruptible(Dispatchers.IO) { ... }`. `runInterruptible` means coroutine cancellation calls `Thread.interrupt()` on the blocked IO thread, breaking out of gRPC socket reads.

**Files:**
- Modify: `maestro-client/src/main/java/maestro/Maestro.kt`

- [ ] **Step 1: Add coroutine imports and convert all methods**

Add imports at top of `Maestro.kt`:

```kotlin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
```

Convert every public method. The pattern for simple methods:

```kotlin
// BEFORE
fun stopApp(appId: String) {
    LOGGER.info("Stopping app $appId")
    driver.stopApp(appId)
}

// AFTER
suspend fun stopApp(appId: String) = runInterruptible(Dispatchers.IO) {
    LOGGER.info("Stopping app $appId")
    driver.stopApp(appId)
}
```

Apply this `suspend fun ... = runInterruptible(Dispatchers.IO) { ... }` pattern to ALL of these methods:
- `launchApp`, `stopApp`, `killApp`, `clearAppState`, `setPermissions`, `clearKeychain`
- `backPress`, `hideKeyboard`, `isKeyboardVisible`
- `swipe` (3 overloads), `swipeFromCenter`, `scrollVertical`
- `tap` (element overload, coordinate overload), `tapOnRelative`
- `pressKey`, `viewHierarchy`, `allElementsMatching`
- `waitForAppToSettle`
- `inputText`, `openLink`, `addMedia`
- `takeScreenshot` (both overloads), `startScreenRecording`
- `setLocation`, `setOrientation`, `eraseText`, `waitForAnimationToEnd`
- `setProxy`, `resetProxy`
- `isShutDown`, `isUnicodeInputSupported`, `isAirplaneModeEnabled`, `setAirplaneModeState`, `setAndroidChromeDevToolsEnabled`
- `deviceInfo` (deprecated)

Private methods `performTap`, `hierarchyBasedTap`, `screenshotBasedTap`, `waitUntilVisible`, `getNumberOfRetries` also become `suspend` since they're called from suspend contexts. For private methods that don't directly call driver (like `getNumberOfRetries`), just add `suspend` keyword — no `runInterruptible` wrapper needed.

**Do NOT change:**
- `close()` — implements `AutoCloseable`, stays non-suspend
- `cachedDeviceInfo` — lazy property, stays as-is
- `deviceName` — property getter, stays as-is
- Companion object factory methods (`ios`, `android`, `web`) — these call `driver.open()` which is a one-time setup, not a hot path. Leave non-suspend.

- [ ] **Step 2: Verify maestro-client compiles**

Run: `./gradlew :maestro-client:compileKotlin 2>&1 | tail -30`

Expected: `maestro-client` compiles. Downstream modules will have errors (expected — callers aren't updated yet).

- [ ] **Step 3: Commit**

```bash
git add maestro-client/src/main/java/maestro/Maestro.kt
git commit -m "feat: make Maestro methods suspend with runInterruptible(Dispatchers.IO)"
```

---

### Task 2: Convert Maestro's polling loops to coroutine-native primitives

`findElementWithTimeout`, `findElementsByOnDeviceQuery`, and `waitUntilVisible` currently use `MaestroTimer.withTimeout` (busy-wait polling) or hardcoded `repeat(10)` with `MaestroTimer.sleep`. Convert to `kotlinx.coroutines.withTimeoutOrNull` and `delay()`. Also convert `Thread.sleep` in tap repeat logic to `delay`.

**Files:**
- Modify: `maestro-client/src/main/java/maestro/Maestro.kt`

- [ ] **Step 1: Add coroutine timeout imports**

```kotlin
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
```

- [ ] **Step 2: Convert findElementWithTimeout**

```kotlin
// BEFORE
fun findElementWithTimeout(
    timeoutMs: Long,
    filter: ElementFilter,
    viewHierarchy: ViewHierarchy? = null
): FindElementResult? {
    var hierarchy = viewHierarchy ?: ViewHierarchy(TreeNode())
    val element = MaestroTimer.withTimeout(timeoutMs) {
        hierarchy = viewHierarchy ?: viewHierarchy()
        filter(hierarchy.aggregate()).firstOrNull()
    }?.toUiElementOrNull()
    return if (element == null) {
        null
    } else {
        if (viewHierarchy != null) {
            hierarchy = ViewHierarchy(element.treeNode)
        }
        return FindElementResult(element, hierarchy)
    }
}

// AFTER
suspend fun findElementWithTimeout(
    timeoutMs: Long,
    filter: ElementFilter,
    initialHierarchy: ViewHierarchy? = null
): FindElementResult? {
    var hierarchy = initialHierarchy ?: ViewHierarchy(TreeNode())
    val element = withTimeoutOrNull(timeoutMs) {
        while (true) {
            hierarchy = initialHierarchy ?: runInterruptible(Dispatchers.IO) {
                ViewHierarchy.from(driver, false)
            }
            val found = filter(hierarchy.aggregate()).firstOrNull()
            if (found != null) break found
            delay(100)
        }
    }?.toUiElementOrNull()

    return if (element == null) {
        null
    } else {
        if (initialHierarchy != null) {
            hierarchy = ViewHierarchy(element.treeNode)
        }
        FindElementResult(element, hierarchy)
    }
}
```

Note: the parameter rename from `viewHierarchy` to `initialHierarchy` avoids shadowing the `viewHierarchy()` method. Check that callers use named arguments — if they don't, the rename is binary-compatible. If any caller uses `viewHierarchy = ...` as a named arg, update it to `initialHierarchy = ...`.

- [ ] **Step 3: Convert findElementsByOnDeviceQuery**

```kotlin
// BEFORE
fun findElementsByOnDeviceQuery(
    timeoutMs: Long,
    query: OnDeviceElementQuery
): OnDeviceElementQueryResult? {
    return MaestroTimer.withTimeout(timeoutMs) {
        val elements = driver.queryOnDeviceElements(query)
        OnDeviceElementQueryResult(
            elements = elements.mapNotNull { it.toUiElementOrNull() },
        )
    }
}

// AFTER
suspend fun findElementsByOnDeviceQuery(
    timeoutMs: Long,
    query: OnDeviceElementQuery
): OnDeviceElementQueryResult? {
    return withTimeoutOrNull(timeoutMs) {
        while (true) {
            val result = runInterruptible(Dispatchers.IO) {
                val elements = driver.queryOnDeviceElements(query)
                OnDeviceElementQueryResult(
                    elements = elements.mapNotNull { it.toUiElementOrNull() },
                )
            }
            if (result.elements.isNotEmpty()) break result
            delay(100)
        }
    }
}
```

- [ ] **Step 4: Convert waitUntilVisible**

```kotlin
// BEFORE
private fun waitUntilVisible(element: UiElement): ViewHierarchy {
    var hierarchy = ViewHierarchy(TreeNode())
    repeat(10) {
        hierarchy = viewHierarchy()
        if (!hierarchy.isVisible(element.treeNode)) {
            LOGGER.info("Element is not visible yet. Waiting.")
            MaestroTimer.sleep(MaestroTimer.Reason.WAIT_UNTIL_VISIBLE, 1000)
        } else {
            LOGGER.info("Element became visible.")
            return hierarchy
        }
    }
    return hierarchy
}

// AFTER
private suspend fun waitUntilVisible(element: UiElement): ViewHierarchy {
    var hierarchy = ViewHierarchy(TreeNode())
    repeat(10) {
        hierarchy = viewHierarchy()
        if (hierarchy.isVisible(element.treeNode)) {
            LOGGER.info("Element became visible.")
            return hierarchy
        }
        LOGGER.info("Element is not visible yet. Waiting.")
        delay(1000)
    }
    return hierarchy
}
```

- [ ] **Step 5: Convert Thread.sleep in tap repeat logic**

In both `hierarchyBasedTap` and `screenshotBasedTap`:

```kotlin
// BEFORE
if (tapRepeat.repeat > 1) Thread.sleep(delay)

// AFTER
if (tapRepeat.repeat > 1) delay(delay)
```

Note: `startScreenRecording`'s `Thread.sleep(durationPadding)` inside `ScreenRecording.close()` stays as-is — `close()` is non-suspend (`Closeable` interface).

- [ ] **Step 6: Remove MaestroTimer import from Maestro.kt**

```kotlin
// Remove this import (no longer used in Maestro.kt)
import maestro.utils.MaestroTimer
```

- [ ] **Step 7: Verify maestro-client compiles**

Run: `./gradlew :maestro-client:compileKotlin 2>&1 | tail -30`

Expected: Compiles cleanly.

- [ ] **Step 8: Commit**

```bash
git add maestro-client/src/main/java/maestro/Maestro.kt
git commit -m "feat: convert Maestro polling loops to coroutine-native withTimeout/delay"
```

---

### Task 3: Update Traveller to be suspend-aware

`Traveller.travel()` is called from Orchestra (suspend context) and calls `maestro.setLocation()` (now suspend). It also uses `Thread.sleep` for pacing GPS simulation.

**Files:**
- Modify: `maestro-orchestra/src/main/java/maestro/orchestra/geo/Traveller.kt`

- [ ] **Step 1: Make travel methods suspend, convert Thread.sleep to delay**

```kotlin
// BEFORE
package maestro.orchestra.geo

import maestro.Maestro
import maestro.orchestra.TravelCommand
import java.util.LinkedList

object Traveller {

    fun travel(
        maestro: Maestro,
        points: List<TravelCommand.GeoPoint>,
        speedMPS: Double,
    ) {
        if (points.isEmpty()) {
            return
        }
        val pointsQueue = LinkedList(points)
        var start = pointsQueue.poll()
        maestro.setLocation(start.latitude, start.longitude)
        do {
            val next = pointsQueue.poll() ?: return
            travel(maestro, start, next, speedMPS)
            start = next
        } while (pointsQueue.isNotEmpty())
    }

    private fun travel(
        maestro: Maestro,
        start: TravelCommand.GeoPoint,
        end: TravelCommand.GeoPoint,
        speedMPS: Double,
    ) {
        val steps = 50
        val distance = start.getDistanceInMeters(end)
        val timeToTravel = distance / speedMPS
        val timeToTravelInMilliseconds = (timeToTravel * 1000).toLong()
        val timeToSleep = timeToTravelInMilliseconds / steps

        val sLat = start.latitude.toDouble()
        val sLon = start.longitude.toDouble()
        val eLat = end.latitude.toDouble()
        val eLon = end.longitude.toDouble()
        val latitudeStep = (eLat - sLat) / steps
        val longitudeStep = (eLon - sLon) / steps

        for (i in 1..steps) {
            val latitude = sLat + (latitudeStep * i)
            val longitude = sLon + (longitudeStep * i)
            maestro.setLocation(latitude.toString(), longitude.toString())
            Thread.sleep(timeToSleep)
        }
    }
}

// AFTER
package maestro.orchestra.geo

import kotlinx.coroutines.delay
import maestro.Maestro
import maestro.orchestra.TravelCommand
import java.util.LinkedList

object Traveller {

    suspend fun travel(
        maestro: Maestro,
        points: List<TravelCommand.GeoPoint>,
        speedMPS: Double,
    ) {
        if (points.isEmpty()) {
            return
        }
        val pointsQueue = LinkedList(points)
        var start = pointsQueue.poll()
        maestro.setLocation(start.latitude, start.longitude)
        do {
            val next = pointsQueue.poll() ?: return
            travel(maestro, start, next, speedMPS)
            start = next
        } while (pointsQueue.isNotEmpty())
    }

    private suspend fun travel(
        maestro: Maestro,
        start: TravelCommand.GeoPoint,
        end: TravelCommand.GeoPoint,
        speedMPS: Double,
    ) {
        val steps = 50
        val distance = start.getDistanceInMeters(end)
        val timeToTravel = distance / speedMPS
        val timeToTravelInMilliseconds = (timeToTravel * 1000).toLong()
        val timeToSleep = timeToTravelInMilliseconds / steps

        val sLat = start.latitude.toDouble()
        val sLon = start.longitude.toDouble()
        val eLat = end.latitude.toDouble()
        val eLon = end.longitude.toDouble()
        val latitudeStep = (eLat - sLat) / steps
        val longitudeStep = (eLon - sLon) / steps

        for (i in 1..steps) {
            val latitude = sLat + (latitudeStep * i)
            val longitude = sLon + (longitudeStep * i)
            maestro.setLocation(latitude.toString(), longitude.toString())
            delay(timeToSleep)
        }
    }
}
```

- [ ] **Step 2: Verify orchestra compiles**

Run: `./gradlew :maestro-orchestra:compileKotlin 2>&1 | tail -30`

Expected: Compiles. Orchestra's methods that call `maestro.*` are already `suspend`.

- [ ] **Step 3: Commit**

```bash
git add maestro-orchestra/src/main/java/maestro/orchestra/geo/Traveller.kt
git commit -m "feat: make Traveller suspend-aware, convert Thread.sleep to delay"
```

---

### Task 4: Fix non-suspend callers with runBlocking

Several CLI tools and Studio code call `Maestro` methods from non-suspend contexts. These need `runBlocking { }` wrappers.

**Files:**
- Modify: `maestro-cli/src/main/java/maestro/cli/util/ScreenshotUtils.kt`
- Modify: `maestro-cli/src/main/java/maestro/cli/runner/TestRunner.kt`
- Modify: `maestro-cli/src/main/java/maestro/cli/command/RecordCommand.kt`
- Modify: `maestro-cli/src/main/java/maestro/cli/command/StudioCommand.kt`
- Modify: `maestro-cli/src/main/java/maestro/cli/command/PrintHierarchyCommand.kt`
- Modify: `maestro-studio/server/src/main/java/maestro/studio/DeviceService.kt`
- Modify: `maestro-cli/src/main/java/maestro/cli/mcp/tools/TakeScreenshotTool.kt`
- Modify: `maestro-cli/src/main/java/maestro/cli/mcp/tools/InspectViewHierarchyTool.kt`

- [ ] **Step 1: Fix maestro-cli/util/ScreenshotUtils.kt**

Add `import kotlinx.coroutines.runBlocking`. Wrap the two `maestro.takeScreenshot` calls:

```kotlin
// Line 24 (in takeDebugScreenshot):
// BEFORE
maestro.takeScreenshot(out.sink(), false)
// AFTER
runBlocking { maestro.takeScreenshot(out.sink(), false) }

// Line 43 (in takeDebugScreenshotByCommand):
// BEFORE
maestro.takeScreenshot(out.sink(), false)
// AFTER
runBlocking { maestro.takeScreenshot(out.sink(), false) }
```

- [ ] **Step 2: Fix TestRunner.kt**

Add `import kotlinx.coroutines.runBlocking`. Wrap `maestro.isShutDown()`:

```kotlin
// Line 209:
// BEFORE
if (!maestro.isShutDown()) {
// AFTER
if (!runBlocking { maestro.isShutDown() }) {
```

- [ ] **Step 3: Fix RecordCommand.kt**

Add `import kotlinx.coroutines.runBlocking`. Wrap `maestro.startScreenRecording`:

```kotlin
// Line 152:
// BEFORE
maestro.startScreenRecording(out).use {
// AFTER
runBlocking { maestro.startScreenRecording(out) }.use {
```

- [ ] **Step 4: Fix StudioCommand.kt**

Add `import kotlinx.coroutines.runBlocking`. Wrap the call:

```kotlin
// Line 84:
// BEFORE
session.maestro.setAndroidChromeDevToolsEnabled(androidWebViewHierarchy == "devtools")
// AFTER
runBlocking { session.maestro.setAndroidChromeDevToolsEnabled(androidWebViewHierarchy == "devtools") }
```

- [ ] **Step 5: Fix PrintHierarchyCommand.kt**

Add `import kotlinx.coroutines.runBlocking`. Wrap both calls:

```kotlin
// Line 118:
// BEFORE
session.maestro.setAndroidChromeDevToolsEnabled(androidWebViewHierarchy == "devtools")
// AFTER
runBlocking { session.maestro.setAndroidChromeDevToolsEnabled(androidWebViewHierarchy == "devtools") }

// Line 132:
// BEFORE
val tree = session.maestro.viewHierarchy().root
// AFTER
val tree = runBlocking { session.maestro.viewHierarchy() }.root
```

- [ ] **Step 6: Fix DeviceService.kt**

This file has 3 direct `maestro.*` calls outside `runBlocking`:
- Line 185: `maestro.viewHierarchy()` (in `getDeviceScreen`)
- Line 194: `maestro.deviceInfo()` (in `getDeviceScreen`)
- Line 233: `maestro.takeScreenshot(...)` (in `takeScreenshot`)

And line 114 already uses `runBlocking { Orchestra(...).runFlow() }` — that one's fine.

Add `import kotlinx.coroutines.runBlocking`. Wrap the three direct calls:

```kotlin
// Line 185:
// BEFORE
tree = maestro.viewHierarchy().root
// AFTER
tree = runBlocking { maestro.viewHierarchy() }.root

// Line 194:
// BEFORE
val deviceInfo = maestro.deviceInfo()
// AFTER
@Suppress("DEPRECATION")
val deviceInfo = runBlocking { maestro.deviceInfo() }

// Line 233:
// BEFORE
maestro.takeScreenshot(screenshotFile, true)
// AFTER
runBlocking { maestro.takeScreenshot(screenshotFile, true) }
```

- [ ] **Step 7: Fix TakeScreenshotTool.kt**

Add `import kotlinx.coroutines.runBlocking`. Wrap the call:

```kotlin
// Line 48:
// BEFORE
session.maestro.takeScreenshot(buffer, true)
// AFTER
runBlocking { session.maestro.takeScreenshot(buffer, true) }
```

- [ ] **Step 8: Fix InspectViewHierarchyTool.kt**

Add `import kotlinx.coroutines.runBlocking`. Wrap the call:

```kotlin
// Line 48:
// BEFORE
val viewHierarchy = maestro.viewHierarchy()
// AFTER
val viewHierarchy = runBlocking { maestro.viewHierarchy() }
```

- [ ] **Step 9: Search for any remaining non-suspend callers**

Run: `./gradlew compileKotlin 2>&1 | grep "Suspend function .* should be called only from a coroutine"`

Expected: No matches. If there are hits, apply the same `runBlocking` pattern.

- [ ] **Step 10: Verify full project compiles**

Run: `./gradlew compileKotlin 2>&1 | tail -30`

Expected: Clean compile across all modules.

- [ ] **Step 11: Commit**

```bash
git add -A
git commit -m "fix: add runBlocking at non-suspend call sites for suspend-native Maestro"
```

---

### Task 5: Convert Orchestra's withTimeoutSuspend to coroutine-native

Orchestra has one remaining `MaestroTimer.withTimeoutSuspend` call. Convert it to `kotlinx.coroutines.withTimeoutOrNull` for consistency with the rest of the coroutine-native approach.

**Files:**
- Modify: `maestro-orchestra/src/main/java/maestro/orchestra/Orchestra.kt`
- Modify: `maestro-utils/src/main/kotlin/MaestroTimer.kt` (if `withTimeoutSuspend` becomes unused)

- [ ] **Step 1: Read the withTimeoutSuspend usage in Orchestra**

Read Orchestra.kt around line 954 to understand what it does.

- [ ] **Step 2: Convert to kotlinx.coroutines.withTimeoutOrNull**

The existing `MaestroTimer.withTimeoutSuspend` is a polling loop with `System.currentTimeMillis()`. Replace with `withTimeoutOrNull` + `delay` loop (same pattern as Task 2).

Add import if not present:
```kotlin
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
```

- [ ] **Step 3: Remove withTimeoutSuspend from MaestroTimer if unused**

Check: `grep -r "withTimeoutSuspend" --include="*.kt" .`

If Orchestra was the only caller, remove the `withTimeoutSuspend` method from `MaestroTimer.kt`.

- [ ] **Step 4: Verify tests pass**

Run: `./gradlew :maestro-orchestra:test :maestro-test:test 2>&1 | tail -30`

Expected: All tests pass.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: replace MaestroTimer.withTimeoutSuspend with coroutine-native withTimeoutOrNull"
```

---

### Task 6: Final verification

- [ ] **Step 1: Full build**

Run: `./gradlew build 2>&1 | tail -50`

Expected: Clean build with all tests passing.

- [ ] **Step 2: Verify no MaestroTimer usage in Maestro.kt**

Run: `grep "MaestroTimer" maestro-client/src/main/java/maestro/Maestro.kt`

Expected: No matches.

- [ ] **Step 3: Verify no SuspendingMaestro anywhere**

Run: `grep -r "SuspendingMaestro" --include="*.kt" .`

Expected: No matches (never existed on this branch).

- [ ] **Step 4: Commit any final fixes**

If any adjustments were needed, commit them.
