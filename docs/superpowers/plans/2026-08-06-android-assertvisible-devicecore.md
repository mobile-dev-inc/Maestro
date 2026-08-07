# Android `assertVisible` → device-core `inspect()` (slot lease) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove that a real legacy Maestro Android flow can have one `assertVisible` served by maestro-device-core's `inspect()` while legacy's runner stays co-resident and functional across the `UiAutomation` slot lease round-trip.

**Architecture:** Reuse the iOS milestone-4 routing seam unchanged (routability predicate + verdict adapter + Orchestra decision) and add the one thing iOS didn't need — a two-layer `UiAutomation` slot lease. Legacy release/re-acquire is host-driven over legacy's existing gRPC channel (two default-no-op `Driver` methods, overridden in `AndroidDriver`); device-core is changed to acquire the slot transiently, on demand, inside its snapshot op. The host serializes the two layers so we never acquire against a still-held slot.

**Tech Stack:** Kotlin, Gradle (Kotlin DSL), gRPC/protobuf (`maestro_android.proto`), AndroidX `UiAutomation`/`UiDevice`, adb/dadb, the `maestro` CLI, an Android emulator.

## Global Constraints

Every task's requirements implicitly include these. Values copied verbatim from the spec (`docs/superpowers/specs/2026-08-06-android-assertvisible-devicecore-design.md`) and the `android-slot-lease` spike.

- **Never re-acquire against a still-held slot.** One lost race wedges the loser in `CONNECTING` for its process life. Gate acquisition on the `already registered!` `IllegalStateException` back-off; on that signal, surface `DeviceCoreUnavailable` — never retry into a wedge.
- **Lease with default accessibility flags**, never `FLAG_DONT_USE_ACCESSIBILITY`. Re-apply `FLAG_RETRIEVE_INTERACTIVE_WINDOWS` / `FLAG_INCLUDE_NOT_IMPORTANT_VIEWS` / `FLAG_REPORT_VIEW_IDS` on each acquire (without them `getWindows()` is empty).
- **Release = `UiAutomation.destroy()`** (a `@hide @TestApi` method, invoked reflectively).
- **Transient, not continuous.** device-core holds the slot only for the one snapshot; legacy is the resting holder.
- **The re-fetch happens in a `finally`.** If `inspect()` throws, legacy must still get its slot back, or it's dead for the run.
- **Env gate:** the router is built only when `MAESTRO_DEVICECORE_ASSERT=1`.
- **Ports:** legacy gRPC = device TCP `7001` (tunneled over dadb, no host `adb forward`); device-core server default = `8791`. They already differ — don't collide them.
- **Latency method:** measure transition cost with on-device `elapsedRealtimeNanos` stamps (`destroy()` returning → handle `CONNECTED`), never host wall-clock. Take the `dumpsys` witness on a *separate* cycle from the timed one so it can't taint the number.
- **Prototype, local-only.** This is a throwaway spike across two worktrees. Commits in this plan are **local worktree checkpoints only — never pushed, nothing lands upstream.** device-core is consumed via `publishToMavenLocal`, not a committed dependency change.

---

## File Structure

Two worktrees. Paths below are relative to each worktree root.

**Maestro worktree** (branch off `devicecore-integration-prototype`, the iOS PR #3487 branch):
- `maestro-proto/src/main/proto/maestro_android.proto` — MODIFY: add `releaseSlot`/`reacquireSlot` RPCs (reuse `EmptyRequest`/`EmptyResponse`).
- `maestro-android/src/androidTest/java/dev/mobile/maestro/MaestroDriverService.kt` — MODIFY: make cached handles reassignable; add device-side `releaseSlot`/`reacquireSlot` handlers (release = destroy; reacquire minimal at first, real in Task 9).
- `maestro-android/src/androidTest/java/dev/mobile/maestro/ToastAccessibilityListener.kt` — MODIFY (Task 9): allow re-registration after re-acquire.
- `maestro-client/src/main/java/maestro/Driver.kt` — MODIFY: add `releaseSlot()`/`reacquireSlot()` as default no-ops.
- `maestro-client/src/main/java/maestro/drivers/AndroidDriver.kt` — MODIFY: override the two methods via `connection.execute(...)`.
- `maestro-orchestra/src/main/java/maestro/orchestra/devicecore/DeviceCoreAssertRouter.kt` — MODIFY: platform-gate (Android provider/target/port property; `fromEnvOrNull` branch); wire the lease into `evaluate()`.
- `maestro-orchestra/src/test/kotlin/maestro/orchestra/devicecore/DeviceCoreAssertRouterTest.kt` and `FakeDeviceProvider.kt` — MODIFY/REUSE: Android-path unit tests.
- `settings.gradle.kts` — no change (device-core comes from `mavenLocal()`, already declared).
- `e2e/workspaces/devicecore/android-assert.yaml` — CREATE: the bespoke acceptance flow.
- `scripts/lease-witness.sh` (or under `e2e/`) — CREATE (Task 7): the `dumpsys accessibility` sampler.

**device-core worktree** (branch off `main`):
- The on-device snapshot server + `UiAutomationDriver` — MODIFY (Task 2): acquire-on-demand transiently in the snapshot op with the `already registered!` back-off. Exact files discovered in Task 2 (start at `drivers/uiautomation/android/UiAutomationDriver.kt` and the androidTest `DriverServerTest.kt` that calls `serve(port, UiAutomationDriver.ops(ua))`).

Reused untouched (no task modifies them): `DeviceCoreRouting.kt`, `AssertVisibleVerdict.kt`, the `Orchestra.kt:514-541` decision, and device-core's `UiAutomationSnapshotLocateStrategy` / `AndroidDeviceProvider` / `AndroidScreen` / `AndroidLocator`.

---

### Task 1: Two worktrees + local device-core via mavenLocal + emulator sanity

**Files:**
- Create: two git worktrees (no file edits yet).
- Verify against: `maestro-orchestra/build.gradle.kts:28` (the `dev.mobile.devicecore:prototype:0.1.0-SNAPSHOT` dependency), `settings.gradle.kts` (`mavenLocal { content { includeGroup("dev.mobile.devicecore") } }`).

**Interfaces:**
- Produces: a Maestro worktree that compiles against a locally-published device-core `0.1.0-SNAPSHOT`, and a device-core worktree ready to modify + republish. A booted emulator visible to `adb`.

- [ ] **Step 1: Create the two worktrees**

```bash
# Maestro side — branch off the iOS PR branch
cd /Users/stevieclifton/codes/Maestro
git worktree add /Users/stevieclifton/codes/worktrees/maestro/android-slot-lease -b android-slot-lease devicecore-integration-prototype

# device-core side — branch off main
cd /Users/stevieclifton/codes/maestro-device-core
git worktree add /Users/stevieclifton/codes/worktrees/maestro-device-core/android-transient-slot -b android-transient-slot main
```

- [ ] **Step 2: Publish device-core to mavenLocal from its worktree**

device-core builds with its own Gradle (9.5.1/JVM 21), so publish it with its own wrapper — do NOT try to `includeBuild` it into Maestro (Gradle-version skew).

```bash
cd /Users/stevieclifton/codes/worktrees/maestro-device-core/android-transient-slot
./gradlew :prototype:publishToMavenLocal
ls ~/.m2/repository/dev/mobile/devicecore/prototype/0.1.0-SNAPSHOT/
```
Expected: a `prototype-0.1.0-SNAPSHOT.jar` under `~/.m2/...`.

- [ ] **Step 3: Verify Maestro resolves device-core from mavenLocal and compiles**

```bash
cd /Users/stevieclifton/codes/worktrees/maestro/android-slot-lease
./gradlew :maestro-orchestra:compileKotlin --refresh-dependencies
```
Expected: BUILD SUCCESSFUL, resolving `dev.mobile.devicecore:prototype:0.1.0-SNAPSHOT` from `mavenLocal`. If it hits GitHub Packages instead, confirm `~/.m2` has the jar (Step 2) and that the version string matches exactly.

- [ ] **Step 4: Confirm an emulator is booted**

```bash
adb devices
```
Expected: exactly one `emulator-XXXX   device`. If none, start one (`emulator -avd <name>` or Android Studio). Single emulator only — device-core ignores adb serial.

- [ ] **Step 5: Commit the checkpoint (local only)**

```bash
cd /Users/stevieclifton/codes/worktrees/maestro/android-slot-lease
git commit --allow-empty -m "chore: worktree bring-up, device-core via mavenLocal [prototype]"
```

---

### Task 2: device-core — transient acquire-on-demand snapshot with back-off

**Files (device-core worktree):**
- Modify: `drivers/uiautomation/android/UiAutomationDriver.kt` (the `snapshot` op that calls `ua.windows`, ~line 104; `serviceFlags` at ~61-65; flag application at ~563-566).
- Modify: the androidTest server that calls `serve(port, UiAutomationDriver.ops(ua))` (`.../DriverServerTest.kt`) — stop capturing an eagerly-held `ua`.

**Interfaces:**
- Produces: a device-core snapshot op that, per request, acquires `UiAutomation` via `InstrumentationRegistry.getInstrumentation().getUiAutomation()`, applies the three accessibility flags to `serviceInfo`, reads `getWindows()`, then `destroy()`s — holding the slot only for the read. On `IllegalStateException("...already registered!")` it fails the request cleanly (a recognizable "slot busy") rather than retrying.

- [ ] **Step 1: Read the current server + op to confirm the eager-hold shape**

Read `drivers/uiautomation/android/UiAutomationDriver.kt` and the androidTest `DriverServerTest.kt`. Confirm `ua` is grabbed once at `serve()` and captured into `ops(ua)`. This is the thing to invert.

- [ ] **Step 2: Write a failing on-device check — two sequential snapshots each succeed**

device-core has an instrumentation/rig harness; find its snapshot-run entrypoint (look under `conformance/`, `drivers/_rig/`, or a `connectedAndroidTest` task). Write (or adapt) an instrumentation test that calls the `snapshot` op **twice in a row** and asserts each returns non-empty windows. With eager-hold + a single captured `ua`, add an assertion that the op re-acquired (e.g. a fresh connection id each call) — this fails today.

Run it (discover the exact command; typically):
```bash
cd /Users/stevieclifton/codes/worktrees/maestro-device-core/android-transient-slot
./gradlew :drivers-core:connectedAndroidTest   # or the rig's documented run command
```
Expected: FAIL (op holds one slot continuously; no per-call acquire/release).

- [ ] **Step 3: Implement acquire-on-demand in the snapshot op**

Restructure so `ops(...)` no longer closes over a held `ua`. In the `snapshot` op body:

```kotlin
// pseudo-shape — adapt to the real op signature
val inst = InstrumentationRegistry.getInstrumentation()
val ua = try {
    inst.uiAutomation                       // acquire; may throw "…already registered!"
} catch (e: IllegalStateException) {
    if (e.message?.contains("already registered") == true) {
        throw SlotBusy("UiAutomation slot held by another tenant")   // clean back-off, no retry
    } else throw e
}
try {
    ua.serviceInfo = ua.serviceInfo.apply {
        flags = flags or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
    }
    val wins = ua.windows.sortedByDescending { it.layer }
    // ...build the snapshot exactly as before...
} finally {
    UiAutomation::class.java.getMethod("destroy").invoke(ua)   // release
}
```

- [ ] **Step 4: Run the check to verify it passes**

Re-run Step 2's test. Expected: PASS — both snapshots populated, each a fresh acquire/release.

- [ ] **Step 5: Republish to mavenLocal**

```bash
cd /Users/stevieclifton/codes/worktrees/maestro-device-core/android-transient-slot
./gradlew :prototype:publishToMavenLocal
```

- [ ] **Step 6: Commit (device-core worktree, local only)**

```bash
git commit -am "feat(android): transient acquire-on-demand snapshot with already-registered back-off [prototype]"
```

---

### Task 3: Proto + legacy device-side `releaseSlot`/`reacquireSlot` (characterization-ready)

**Files (Maestro worktree):**
- Modify: `maestro-proto/src/main/proto/maestro_android.proto` (service `MaestroDriver`, ~lines 5-13; `EmptyRequest`/`EmptyResponse` already exist).
- Modify: `maestro-android/src/androidTest/java/dev/mobile/maestro/MaestroDriverService.kt` (the `Service` class at ~115-118; `grpcServer()` at ~85; acquisition at ~92-94; listener at ~125).

**Interfaces:**
- Produces (proto): `rpc releaseSlot(EmptyRequest) returns (EmptyResponse)`, `rpc reacquireSlot(EmptyRequest) returns (EmptyResponse)` — generated Kotlin stub methods `releaseSlot(...)` / `reacquireSlot(...)` on `MaestroDriverGrpc.MaestroDriverBlockingStub` and DSL `emptyRequest {}`.
- Produces (device): `Service.releaseSlot` destroys the cached `UiAutomation`; `Service.reacquireSlot` is a logging **no-op** at this stage (legacy keeps its stale handle — required for the Task 8 characterization). The cached `uiAutomation`/`uiDevice` are made reassignable so Task 9 can re-fetch.

- [ ] **Step 1: Add the two RPCs to the proto**

In `maestro_android.proto`, inside `service MaestroDriver { ... }`:
```proto
  rpc releaseSlot(EmptyRequest) returns (EmptyResponse) {}
  rpc reacquireSlot(EmptyRequest) returns (EmptyResponse) {}
```

- [ ] **Step 2: Regenerate + confirm the stubs compile host-side**

```bash
cd /Users/stevieclifton/codes/worktrees/maestro/android-slot-lease
./gradlew :maestro-proto:build
```
Expected: BUILD SUCCESSFUL; `MaestroDriverGrpc` now has `releaseSlot`/`reacquireSlot`.

- [ ] **Step 3: Make the cached handles reassignable + add device-side handlers**

In `MaestroDriverService.kt`, change the `Service` fields from `private val uiDevice`/`private val uiAutomation` to reassignable (`private var`), and hold the `Instrumentation` so re-fetch is possible. Add:

```kotlin
override fun releaseSlot(request: EmptyRequest, responseObserver: StreamObserver<EmptyResponse>) {
    Log.d("Maestro", "releaseSlot: destroying UiAutomation")
    UiAutomation::class.java.getMethod("destroy").invoke(uiAutomation)
    responseObserver.onNext(emptyResponse {})
    responseObserver.onCompleted()
}

override fun reacquireSlot(request: EmptyRequest, responseObserver: StreamObserver<EmptyResponse>) {
    // CHARACTERIZATION STUB (Task 8): intentionally does NOT re-fetch yet.
    Log.d("Maestro", "reacquireSlot: no-op stub (characterization)")
    responseObserver.onNext(emptyResponse {})
    responseObserver.onCompleted()
}
```

- [ ] **Step 4: Build the device APK**

```bash
./gradlew :maestro-android:assembleAndroidTest
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit (local only)**

```bash
git add maestro-proto maestro-android
git commit -m "feat(android): releaseSlot/reacquireSlot RPCs; reacquire is characterization no-op [prototype]"
```

---

### Task 4: `Driver` interface + `AndroidDriver` host-side slot methods

**Files (Maestro worktree):**
- Modify: `maestro-client/src/main/java/maestro/Driver.kt` (default-no-op idiom already present, e.g. `setAndroidChromeDevToolsEnabled(...) = Unit` ~line 119).
- Modify: `maestro-client/src/main/java/maestro/drivers/AndroidDriver.kt` (delegates transport to `connection.execute(...)`).
- Test: `maestro-client/src/test/.../AndroidDriverSlotTest.kt` (create).

**Interfaces:**
- Consumes: `MaestroDriverGrpc...releaseSlot/reacquireSlot` (Task 3), `AndroidDeviceConnection.execute(op) { stub -> ... }`.
- Produces: `Driver.releaseSlot(): Unit` and `Driver.reacquireSlot(): Unit` (default no-op on the interface; iOS/Web drivers inherit), overridden in `AndroidDriver` to call the RPCs. This is what the router (Task 6) calls via `maestro.driver`.

- [ ] **Step 1: Write the failing test**

```kotlin
// AndroidDriverSlotTest.kt
@Test
fun `releaseSlot invokes the releaseSlot rpc`() {
    val calls = mutableListOf<String>()
    val connection = fakeConnectionRecording(calls)          // records the op label passed to execute(...)
    val driver = AndroidDriver(connection)
    driver.releaseSlot()
    assertEquals(listOf("releaseSlot"), calls)
}
```

- [ ] **Step 2: Run it — expect fail**

```bash
./gradlew :maestro-client:test --tests "*AndroidDriverSlotTest*"
```
Expected: FAIL (`releaseSlot` unresolved / not overridden).

- [ ] **Step 3: Add the interface defaults + AndroidDriver overrides**

In `Driver.kt`:
```kotlin
fun releaseSlot() = Unit
fun reacquireSlot() = Unit
```
In `AndroidDriver.kt`:
```kotlin
override fun releaseSlot() {
    connection.execute("releaseSlot") { it.releaseSlot(emptyRequest {}) }.orThrow()
}
override fun reacquireSlot() {
    connection.execute("reacquireSlot") { it.reacquireSlot(emptyRequest {}) }.orThrow()
}
```

- [ ] **Step 4: Run tests — expect pass**

```bash
./gradlew :maestro-client:test --tests "*AndroidDriverSlotTest*"
```
Expected: PASS.

- [ ] **Step 5: Commit (local only)**

```bash
git add maestro-client
git commit -m "feat: Driver.releaseSlot/reacquireSlot default no-ops, AndroidDriver overrides [prototype]"
```

---

### Task 5: Router Android platform gate

**Files (Maestro worktree):**
- Modify: `maestro-orchestra/src/main/java/maestro/orchestra/devicecore/DeviceCoreAssertRouter.kt` (the six iOS points: `IosDeviceProvider` import/default, `TargetId.IOS_SIM`, `Platform.IOS` check in `fromEnvOrNull`, `devicecore.ios.bundleId` property, `connect(TargetSelector(IOS_SIM))`).
- Test: `maestro-orchestra/src/test/kotlin/maestro/orchestra/devicecore/DeviceCoreAssertRouterTest.kt` + `FakeDeviceProvider.kt` (existing).

**Interfaces:**
- Consumes: `AndroidDeviceProvider`, `TargetId.ANDROID_EMU` (device-core); `Platform.ANDROID`.
- Produces: `DeviceCoreAssertRouter.fromEnvOrNull(maestro, appId)` returns an **Android-configured** router when `MAESTRO_DEVICECORE_ASSERT=1` and `platform == Platform.ANDROID` — provider factory `{ AndroidDeviceProvider() }`, target `TargetId.ANDROID_EMU`, and it sets `devicecore.android.forwardPort` instead of writing `devicecore.ios.bundleId`.

- [ ] **Step 1: Write the failing test (use `FakeDeviceProvider`)**

```kotlin
@Test
fun `android platform builds a router targeting ANDROID_EMU`() {
    val calls = mutableListOf<TargetId>()
    val fake = FakeDeviceProvider(onConnect = { calls += it.target })
    val router = DeviceCoreAssertRouter(appId = "org.wikipedia", providerFactory = { fake })
    runBlocking { router.evaluate(visibleCondition("Search Wikipedia"), 1080, 2400) }
    assertEquals(listOf(TargetId.ANDROID_EMU), calls)
}
```

- [ ] **Step 2: Run it — expect fail**

```bash
./gradlew :maestro-orchestra:test --tests "*DeviceCoreAssertRouterTest*"
```
Expected: FAIL (router still connects with `IOS_SIM`).

- [ ] **Step 3: Parameterize the router for Android**

- Add a `platform`/`target` parameter (or a second `providerFactory` default) so `evaluate()` calls `connect(TargetSelector(target))` with `ANDROID_EMU` on Android.
- Replace the iOS-only `System.setProperty("devicecore.ios.bundleId", appId)` with a platform branch: on Android, `System.setProperty("devicecore.android.forwardPort", port.toString())` (port defaults to `8791`; make it a router field). Keep the iOS branch intact.
- In `fromEnvOrNull`: after the `MAESTRO_DEVICECORE_ASSERT=1` check, branch on `maestro.cachedDeviceInfo.platform` — `IOS` → the existing iOS router; `ANDROID` → an Android router (`providerFactory = { AndroidDeviceProvider() }`, `target = ANDROID_EMU`); else `null`.

- [ ] **Step 4: Run tests — expect pass**

```bash
./gradlew :maestro-orchestra:test --tests "*DeviceCore*"
```
Expected: PASS (Android test green; existing iOS tests still green).

- [ ] **Step 5: Commit (local only)**

```bash
git add maestro-orchestra
git commit -m "feat(android): platform-gate DeviceCoreAssertRouter to AndroidDeviceProvider/ANDROID_EMU [prototype]"
```

---

### Task 6: Wire the lease into the router body

**Files (Maestro worktree):**
- Modify: `maestro-orchestra/src/main/java/maestro/orchestra/devicecore/DeviceCoreAssertRouter.kt` (the `evaluate()` body).
- Test: `DeviceCoreAssertRouterTest.kt`.

**Interfaces:**
- Consumes: `maestro.driver.releaseSlot()` / `maestro.driver.reacquireSlot()` (Task 4). The router needs a reference to the `Maestro` (or its `Driver`) to call these — pass `maestro` into the router (`fromEnvOrNull` already has it) and hold it as a field.
- Produces: an `evaluate()` that calls `releaseSlot()` before `inspect()` and `reacquireSlot()` in a `finally`, on both platforms (no-ops on iOS).

- [ ] **Step 1: Write the failing test — call order + reacquire-on-throw**

```kotlin
@Test
fun `evaluate releases before inspect and reacquires even when inspect throws`() {
    val order = mutableListOf<String>()
    val driver = fakeDriverRecording(order)                 // releaseSlot/reacquireSlot append to order
    val throwingProvider = FakeDeviceProvider(onInspect = { order += "inspect"; error("boom") })
    val router = androidRouter(driver = driver, provider = throwingProvider)
    assertFailsWith<DeviceCoreUnavailable> {
        runBlocking { router.evaluate(visibleCondition("X"), 1080, 2400) }
    }
    assertEquals(listOf("releaseSlot", "inspect", "reacquireSlot"), order)
}
```

- [ ] **Step 2: Run it — expect fail**

```bash
./gradlew :maestro-orchestra:test --tests "*DeviceCoreAssertRouterTest*"
```
Expected: FAIL (no lease calls yet).

- [ ] **Step 3: Wrap `inspect()` with the lease**

```kotlin
maestro.driver.releaseSlot()                         // no-op on iOS
val evidence = try {
    val device = providerFactory().connect(TargetSelector(target))
    val base = device.screen.getByText(query.text, query.match)
    val locator = query.index?.let { base.nth(it) } ?: base
    locator.inspect()
} catch (e: DeviceCoreUnavailable) { throw e
} catch (e: CancellationException) { throw e
} catch (e: Exception) { throw DeviceCoreUnavailable("device-core inspect() failed for '${query.text}': ${e.message}")
} finally {
    maestro.driver.reacquireSlot()                   // no-op on iOS; re-fetch on Android
}
return AssertVisibleVerdict.pass(evidence, query.mode, screenWidthPts, screenHeightPts)
```

- [ ] **Step 4: Run tests — expect pass**

```bash
./gradlew :maestro-orchestra:test --tests "*DeviceCore*"
```
Expected: PASS.

- [ ] **Step 5: Commit (local only)**

```bash
git add maestro-orchestra
git commit -m "feat(android): wrap device-core inspect() with the slot lease (release->inspect->finally reacquire) [prototype]"
```

---

### Task 7: Timing stamps + `dumpsys` witness harness

**Files (Maestro worktree):**
- Modify: `MaestroDriverService.kt` — stamp `elapsedRealtimeNanos()` at `destroy()` return and (in Task 9) at re-acquire `CONNECTED`; log them under a stable tag.
- Modify: device-core's snapshot op (device-core worktree) — same stamps around its acquire/destroy; log under a stable tag. Republish.
- Create: `e2e/workspaces/devicecore/lease-witness.sh` — the out-of-band slot-owner sampler.

**Interfaces:**
- Produces: greppable log lines (`LEASE_TIMING legacy.release=<ns>`, `LEASE_TIMING core.acquire=<ns>`, …) and a `lease-witness.sh` that prints the accessibility slot owner on demand.

- [ ] **Step 1: Add on-device transition stamps**

In each `destroy()`/acquire path (legacy `releaseSlot`/`reacquireSlot`, device-core snapshot acquire/destroy), log:
```kotlin
Log.d("Maestro", "LEASE_TIMING <phase>=${SystemClock.elapsedRealtimeNanos()}")
```

- [ ] **Step 2: Write the `dumpsys` witness sampler**

```bash
#!/usr/bin/env bash
# lease-witness.sh — prints the current UiAutomation/accessibility slot owner from system_server's vantage.
# Run on a SEPARATE cycle from the timed run so it never inflates the latency number.
adb shell dumpsys accessibility | grep -iE "uiautomation|Ui Automation|registered|serviceInfo" || true
```
`chmod +x` it.

- [ ] **Step 3: Dry-run the sampler**

```bash
./e2e/workspaces/devicecore/lease-witness.sh
```
Expected: prints the current owner line(s) without error (legacy holds the slot at rest).

- [ ] **Step 4: Republish device-core (stamps added there) + rebuild the APK**

```bash
( cd /Users/stevieclifton/codes/worktrees/maestro-device-core/android-transient-slot && ./gradlew :prototype:publishToMavenLocal )
./gradlew :maestro-android:assembleAndroidTest
```

- [ ] **Step 5: Commit (local only, both worktrees)**

```bash
git add e2e maestro-android
git commit -m "chore: lease timing stamps + dumpsys witness sampler [prototype]"
```

---

### Task 8: Characterization run (unpatched `reacquireSlot`)

**This is an experiment task. Its deliverable is a finding, not a green flow.** `reacquireSlot` is still the no-op stub from Task 3.

**Files (Maestro worktree):**
- Create: `e2e/workspaces/devicecore/android-assert.yaml` — the bespoke flow.

**Interfaces:**
- Consumes: everything from Tasks 1–7; the `wikipedia.apk` fixture (`e2e/apps/wikipedia.apk`) with a known literal-text element.
- Produces: a written characterization result — which of {direct cached-`uiAutomation` reads, `UiDevice`-routed reads, the toast listener} survive the unpatched round-trip — plus the first `dumpsys` provenance sample and first `LEASE_TIMING` stamps.

- [ ] **Step 1: Write the bespoke flow**

```yaml
# android-assert.yaml
appId: org.wikipedia
---
- launchApp
- assertVisible: "Search Wikipedia"          # ordinary legacy step (baseline)
- assertVisible: "Search Wikipedia"          # THIS ONE routes to device-core (literal text, no metachars)
# --- survival probes: each hits a different cached path ---
- assertVisible: "Search Wikipedia"          # legacy ViewHierarchy.dump(uiAutomation) — direct cached path
- takeScreenshot: survival-screenshot        # uiAutomation.takeScreenshot() — direct cached path
```
(Pick the exact literal string from the wikipedia app's first screen; adjust if "Search Wikipedia" isn't present.)

- [ ] **Step 2: Install the app + build the CLI**

```bash
cd /Users/stevieclifton/codes/worktrees/maestro/android-slot-lease
adb install -r e2e/apps/wikipedia.apk
./gradlew :maestro-cli:installDist -q
```

- [ ] **Step 3: Run routed, unpatched — capture logs and witness**

```bash
adb logcat -c
MAESTRO_DEVICECORE_ASSERT=1 ./maestro-cli/build/install/maestro/bin/maestro \
    --platform android test e2e/workspaces/devicecore/android-assert.yaml -e appId=org.wikipedia
# in parallel / immediately after, sample the witness and pull the lease logs:
./e2e/workspaces/devicecore/lease-witness.sh
adb logcat -d | grep -E "LEASE_TIMING|device-core decided|reacquireSlot|releaseSlot"
```

- [ ] **Step 4: Record the characterization finding**

Note precisely, in the task's review notes:
- Did the routed `assertVisible` (step 2 of the flow) pass and show a `device-core decided assert…` log? (Confirms routing + transient acquire worked.)
- Which survival probe first fails: the post-lease legacy `assertVisible` (direct cached-`uiAutomation`), the `takeScreenshot`, or neither? A crash/`UiAutomation not connected` on the direct path is the expected break.
- Does `UiDevice`-routed behavior differ (if any step uses it)?
- Does the `dumpsys` witness show the slot moved legacy → (device-core) → legacy?

Expected: the routed step succeeds; at least one post-lease **direct cached-`uiAutomation`** step fails (stale handle). That failure set defines Task 9's patch. If nothing breaks, `UiDevice`/framework self-healed — Task 9 shrinks to just the listener (or becomes a no-op) and you note that surprise.

- [ ] **Step 5: Commit the flow + finding (local only)**

```bash
git add e2e/workspaces/devicecore/android-assert.yaml
git commit -m "test(android): bespoke devicecore assert flow + characterization finding [prototype]"
```

---

### Task 9: The re-fetch patch (precise, per Task 8)

**Files (Maestro worktree):**
- Modify: `MaestroDriverService.kt` (`reacquireSlot` handler; reassignable handles from Task 3).
- Modify: `ToastAccessibilityListener.kt` (allow re-registration — the `isListening` guard at ~53 currently blocks re-arm; add a `stop()` that clears the listener, or a `restart(ua)`).

**Interfaces:**
- Produces: `reacquireSlot` rebuilds exactly what Task 8 showed broken — typically: re-fetch `UiAutomation` (`instrumentation.uiAutomation`), re-apply `serviceInfo`, reassign the cached `uiDevice` (`UiDevice.getInstance(instrumentation)`), and re-register `ToastAccessibilityListener` on the fresh handle.

- [ ] **Step 1: Implement re-fetch for the paths Task 8 broke**

```kotlin
override fun reacquireSlot(request: EmptyRequest, responseObserver: StreamObserver<EmptyResponse>) {
    val inst = InstrumentationRegistry.getInstrumentation()
    uiAutomation = inst.uiAutomation                       // fresh slot
    uiDevice = UiDevice.getInstance(inst)                  // rebind cached handle
    ToastAccessibilityListener.restart(uiAutomation)       // re-arm the listener on the new handle
    Log.d("Maestro", "reacquireSlot: re-fetched UiAutomation/UiDevice, re-registered listener")
    responseObserver.onNext(emptyResponse {})
    responseObserver.onCompleted()
}
```
Add `ToastAccessibilityListener.restart(ua)` that clears `isListening` and re-calls `setOnAccessibilityEventListener(this)`.

- [ ] **Step 2: Rebuild the device APK + reinstall the driver**

```bash
./gradlew :maestro-android:assembleAndroidTest
```
(The CLI reinstalls the driver on next run.)

- [ ] **Step 3: Re-run the flow — survival step must pass**

```bash
adb logcat -c
MAESTRO_DEVICECORE_ASSERT=1 ./maestro-cli/build/install/maestro/bin/maestro \
    --platform android test e2e/workspaces/devicecore/android-assert.yaml -e appId=org.wikipedia
```
Expected: the whole flow passes — the post-lease legacy `assertVisible` and `takeScreenshot` succeed. That's the survival proof.

- [ ] **Step 4: Commit (local only)**

```bash
git add maestro-android
git commit -m "feat(android): reacquireSlot re-fetches cached handle + re-registers listener (survival) [prototype]"
```

---

### Task 10: Full acceptance run (positive + survival + negative control + provenance + latency)

**Files (Maestro worktree):**
- Create: `e2e/workspaces/devicecore/android-assert-negative.yaml` — the negative control.

**Interfaces:**
- Consumes: the patched build from Task 9.
- Produces: the acceptance evidence bundle — positive pass, survival pass, negative fail-at-the-routed-step, `dumpsys` provenance, and the measured per-routed-`assertVisible` latency.

- [ ] **Step 1: Positive + survival (already green from Task 9) — capture the evidence**

```bash
adb logcat -c
MAESTRO_DEVICECORE_ASSERT=1 ./maestro-cli/build/install/maestro/bin/maestro \
    --platform android test e2e/workspaces/devicecore/android-assert.yaml -e appId=org.wikipedia
adb logcat -d | grep -E "device-core decided|LEASE_TIMING"
```
Expected: flow PASS; a `device-core decided assert…` line; `LEASE_TIMING` stamps for all four transitions.

- [ ] **Step 2: Negative control — routed assert on an absent element must FAIL at that step**

```yaml
# android-assert-negative.yaml
appId: org.wikipedia
---
- launchApp
- assertVisible: "ThisTextDoesNotExistAnywhere_xyz"     # routes to device-core; must be not-visible
```
```bash
MAESTRO_DEVICECORE_ASSERT=1 ./maestro-cli/build/install/maestro/bin/maestro \
    --platform android test e2e/workspaces/devicecore/android-assert-negative.yaml -e appId=org.wikipedia
echo "exit=$?"
```
Expected: flow FAILS at the routed step; the `device-core decided assertVisible … verdict=false` log proves device-core decided (not a pass-through).

- [ ] **Step 3: Provenance — witness the slot movement on a separate cycle**

Run the positive flow again and, on a **separate** cycle, sample the witness before/during/after (a loop that polls `lease-witness.sh` during the run is fine — it's out-of-band and doesn't touch the timed stamps):
```bash
( while true; do date +%s%N; ./e2e/workspaces/devicecore/lease-witness.sh; sleep 0.05; done ) > /tmp/witness.log &
MAESTRO_DEVICECORE_ASSERT=1 ./maestro-cli/build/install/maestro/bin/maestro \
    --platform android test e2e/workspaces/devicecore/android-assert.yaml -e appId=org.wikipedia
kill %1
```
Expected: `/tmp/witness.log` shows the owner change away from legacy during device-core's hold and back after.

- [ ] **Step 4: Compute the latency number**

From the `LEASE_TIMING` stamps of Step 1, compute per-routed-`assertVisible` overhead = (legacy.release→core.acquire) + (core.release→legacy.reacquire) + snapshot. Record the measured figure and the emulator's API level. Compare to the ~0.2–0.6 s a-priori.

- [ ] **Step 5: Write the acceptance summary + commit (local only)**

Record in the task notes: positive pass, survival pass, negative fail-at-step, witness confirmation, and the measured latency (now `measured`, not `claimed`).
```bash
git add e2e/workspaces/devicecore/android-assert-negative.yaml
git commit -m "test(android): negative control + acceptance evidence (survival proven, latency measured) [prototype]"
```

---

## Self-Review

**Spec coverage** (each spec section → task):
- Routing seam reuse + platform gate → Task 5. ✓
- Two-layer lease (legacy host-driven + device-core transient) → Tasks 2 (device-core), 3 (legacy device-side), 4 (Driver host-side), 6 (router wiring). ✓
- `already registered!` back-off → Task 2. ✓
- Re-fetch patch, in scope, characterize-then-patch → Tasks 3 (stub), 8 (characterize), 9 (patch). ✓
- Success criteria (positive, survival centerpiece, negative control, provenance) → Task 10 (+8, 9). ✓
- Witness + timing, kept independent → Task 7 (build), 10 (use, separate cycles). ✓
- Latency method + estimate → Task 7 (stamps), 10 Step 4 (measure). ✓
- Two worktrees + local device-core consumption → Task 1 (mavenLocal, not includeBuild — Gradle skew). ✓
- Transient-not-continuous, default flags, finally-reacquire, ports → Global Constraints, enforced in Tasks 2/6. ✓

**Placeholder scan:** device-core's exact file to edit in Task 2 is discovered in-task (start point named) because the on-device server file layout wasn't fully pinned at plan time — that's a genuine "read then edit," not a hand-wave; the change and its verification are fully specified. All other steps carry concrete code/commands.

**Type consistency:** `releaseSlot`/`reacquireSlot` named identically across proto (Task 3), `Driver`/`AndroidDriver` (Task 4), and router (Task 6). `TargetId.ANDROID_EMU`, `AndroidDeviceProvider`, `DeviceCoreUnavailable`, `AssertVisibleVerdict.pass` match the recon'd device-core/seam API. `devicecore.android.forwardPort` used consistently (Task 5).

**Known soft spots (flag to reviewer, not blockers):** device-core's instrumentation run command (Task 2 Step 2) and the exact wikipedia literal-text string (Task 8) are discover-in-task; both have a named starting point and a clear expected observable.
