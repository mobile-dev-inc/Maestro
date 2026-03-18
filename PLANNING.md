# Maestro Architecture Planning

## Overview

This document describes concrete problems in the current Maestro codebase and the target architecture that solves them. It is written for contributors, agents, and anyone making changes to Maestro's internals.

**Problems this plan addresses:**

1. Business logic inside `maestro-android` cannot be unit tested — it lives in the `androidTest` instrumentation layer which requires a running device. CI cannot verify correctness of view hierarchy serialization, command routing, or element filtering without hardware.
2. Contributors do not know which files to touch when changing a command's behaviour. There is no map from "I want `hideKeyboard` to behave differently" to "here are the exact files on client and server."
3. The module structure has too many modules with overlapping names (`maestro-client`, `maestro-ios`, `maestro-ios-driver`, `maestro-web`) and no clear rule for what belongs where. New contributors and agents cannot determine the right home for new code.
4. `maestro-core` (the client driver layer) has no stated contract. A contributor changing performance of `hideKeyboard` has no confidence they are not breaking the public-facing API.
5. There is no automated check that a behaviour change does not break existing flows. A PR changing how scroll works can merge without validating real device behaviour.
6. Command execution is slower than it needs to be due to unnecessary round trips and blocking calls that were never profiled because there were no isolated benchmarks.

---

## Current Architecture

```
maestro-cli
  └── maestro-orchestra          (YAML execution engine)
        └── maestro-orchestra-models
        └── maestro-client       (Driver interface + AndroidDriver + CdpWebDriver)
              └── maestro-ios-driver   (XCTest HTTP client + low-level iOS protocol)
              └── maestro-ios          (IOSDriver implementing Driver)
              └── maestro-web          (WebDriver + Selenium + recording)
              └── maestro-utils
              └── maestro-proto        (protobuf for Android gRPC)

maestro-android  (Android APK — the on-device gRPC server)
  src/androidTest/
    MaestroDriverService.kt      ← gRPC server + ALL business logic
    ViewHierarchy.kt             ← XML serialization — untestable, uses InstrumentationRegistry
    AccessibilityNodeInfoExt.kt  ← element utilities — untestable
  src/main/
    handlers/                    ← locale/settings handlers

maestro-ios-xctest-runner  (Swift — the on-device HTTP server)
  MaestroDriverLib/              ← pure Swift package, NO XCUITest imports, unit testable
    Sources/MaestroDriverLib/
      AXElement.swift
      AXFrame.swift
      PermissionButtonFinder.swift
    Tests/                       ← runs with swift test in CI
  maestro-driver-iosUITests/     ← thin XCUITest layer
    Routes/Models/AXElement.swift  ← single bridge: XCUIElement → AXElement
    Routes/Handlers/             ← 18 handlers, all thin, delegate to lib
```

### Problems visible in the current layout

| Problem | Location | Impact |
|---|---|---|
| `ViewHierarchy.kt` calls `InstrumentationRegistry` inside serialization logic | `maestro-android/src/androidTest/` | Cannot unit test XML output without a device |
| `IOSDriver` in `maestro-ios`, `AndroidDriver` in `maestro-client` | different modules | Inconsistent — no rule for where drivers live |
| `CdpWebDriver` in `maestro-client` imports from `maestro-web` | cross-module | Driver and its dependencies split across two modules |
| `maestro-ios` and `maestro-ios-driver` are separate modules | both JVM | No reason for the split; adds indirection |
| No stated contract for `Driver` interface | `maestro-client` | Changes to performance internals risk breaking callers unknowingly |
| No smoke test gating PRs | CI | Behaviour regressions only caught manually |

---

## Target Architecture

### Module map (after)

```
maestro-core                     ← renamed from maestro-client
                                   ALL driver clients live here
  drivers/
    AndroidDriver.kt             ← gRPC client → maestro-android
    IOSDriver.kt                 ← HTTP client → maestro-ios-xctest-runner
    CdpWebDriver.kt              ← CDP/Selenium client → browser
  Driver.kt                      ← stable interface contract (see Contract section)
  Maestro.kt

maestro-android                  ← Android APK (on-device gRPC server)
  MaestroDriverLib/              ← NEW: nested Gradle subproject, kotlin("jvm"), no android deps
    src/main/kotlin/
      NodeInfo.kt                ← platform-agnostic tree node (equivalent of iOS AXElement)
      ViewHierarchySerializer.kt ← pure XML serialization, takes NodeInfo not AccessibilityNodeInfo
      CommandRouter.kt           ← pure command dispatch table
    src/test/kotlin/             ← runs on JVM in CI, no device required
      ViewHierarchySerializerTest.kt
      CommandRouterTest.kt
  src/androidTest/               ← thin instrumentation glue only
    MaestroDriverService.kt      ← gRPC server → CommandRouter (no logic here)
    AndroidBridge.kt             ← SINGLE bridge: AccessibilityNodeInfo → NodeInfo
    UiActions.kt                 ← actual UiDevice.tap(), swipe(), etc.

maestro-ios-xctest-runner        ← unchanged, already follows this pattern
  MaestroDriverLib/              ← pure Swift, unit testable
  maestro-driver-iosUITests/     ← thin XCUITest glue

maestro-proto                    ← protobuf definitions, unchanged
maestro-utils                    ← shared JVM utilities, unchanged
maestro-orchestra                ← YAML execution engine, unchanged
maestro-orchestra-models         ← unchanged
maestro-cli                      ← unchanged
maestro-ai                       ← unchanged
maestro-studio                   ← unchanged
```

### What gets collapsed

| Before | After | Reason |
|---|---|---|
| `maestro-client` | `maestro-core` | Rename to match mental model |
| `maestro-ios` | merged into `maestro-core` | `IOSDriver` belongs next to `AndroidDriver` |
| `maestro-ios-driver` | merged into `maestro-core` | XCTest HTTP client is part of the client layer |
| `maestro-web` | merged into `maestro-core` | `CdpWebDriver` was already there, split was artificial |

### The client-server boundary, made explicit

```
  maestro-core  (JVM, runs on developer machine)
       │
       ├── AndroidDriver ──── gRPC ────► MaestroDriverService
       │                                  (maestro-android/androidTest)
       │                                       │
       │                                  delegates to
       │                                       │
       │                                  CommandRouter + ViewHierarchySerializer
       │                                  (maestro-android/MaestroDriverLib)
       │                                  ← unit testable
       │
       ├── IOSDriver ──────── HTTP ─────► XCTestHTTPServer
       │                                  (maestro-ios-xctest-runner/UITests)
       │                                       │
       │                                  delegates to
       │                                       │
       │                                  MaestroDriverLib (Swift)
       │                                  ← unit testable
       │
       └── CdpWebDriver ───── CDP/WD ───► Browser (Chrome)
```

---

## maestro-core Contract

`maestro-core` owns the `Driver` interface. This interface is the contract between the execution engine (`maestro-orchestra`) and the platform drivers. Once stable, changes to internals (performance, retry logic, timing) must not change the interface.

### Stable surface (do not change without a major version)

```kotlin
interface Driver {
    fun deviceInfo(): DeviceInfo
    fun launchApp(appId: String, ...)
    fun stopApp(appId: String)
    fun tap(point: Point)
    fun swipe(start: Point, end: Point, durationMs: Long)
    fun inputText(text: String)
    fun hideKeyboard()
    fun viewHierarchy(): ViewHierarchy
    fun screenshot(out: Sink, compressed: Boolean)
    fun pressKey(code: KeyCode)
    // ... full list in Driver.kt
}
```

### What is free to change without concern

- Retry counts and backoff in `AndroidDriver`, `IOSDriver`
- Threading model inside a driver
- gRPC/HTTP timeout values
- View hierarchy parsing performance in `MaestroDriverLib`
- Screenshot compression implementation

A contributor changing `hideKeyboard` performance in `IOSDriver` only needs to understand `maestro-core`. They do not need to touch `maestro-orchestra` or `maestro-cli`. The Driver interface is the firewall.

---

## Skills

A skill is a named capability of Maestro. Each skill maps to specific files on the client (maestro-core) and server (MaestroDriverLib + thin glue). See [SKILLS.md](./SKILLS.md) for the full per-command map.

### Skill categories

| Skill | Description |
|---|---|
| **view-hierarchy** | Capturing and serializing the UI element tree from a device |
| **element-finding** | Querying elements from the hierarchy by text, id, type |
| **tap** | Sending a tap interaction to a coordinate or element |
| **swipe** | Sending a swipe gesture |
| **text-input** | Typing text into the focused element |
| **keyboard** | Showing and hiding the software keyboard |
| **app-lifecycle** | Launching, stopping, clearing app state |
| **screenshot** | Capturing device screen as PNG/JPEG |
| **permissions** | Granting and revoking app permissions |
| **location** | Mocking GPS location |
| **orientation** | Changing device orientation |
| **recording** | Recording device screen to video |

### Example: changing hideKeyboard behaviour

1. Read `SKILLS.md` → `keyboard` skill
2. Client logic: `maestro-core/src/main/java/maestro/drivers/IOSDriver.kt` → `hideKeyboard()`
3. Server logic (iOS): `maestro-ios-xctest-runner/MaestroDriverLib/Sources/.../KeyboardHandler.swift`
4. Server glue (iOS): `maestro-ios-xctest-runner/maestro-driver-iosUITests/Routes/Handlers/`
5. Server logic (Android): `maestro-android/MaestroDriverLib/src/main/kotlin/CommandRouter.kt`
6. Write a unit test in `MaestroDriverLib/src/test/` for logic changes
7. Run smoke test (see Smoke Tests section) to confirm no regression

---

## SKILLS.md

A separate `SKILLS.md` file at the repo root maps each Maestro command to the exact files a contributor must read and potentially change, on both client and server, for all platforms. It is the answer to "I want to change X, where do I look?"

Structure:

```markdown
## hideKeyboard

**What it does:** Dismisses the software keyboard if visible.

**Client**
- `maestro-core/src/main/java/maestro/drivers/IOSDriver.kt` → `hideKeyboard()`
- `maestro-core/src/main/java/maestro/drivers/AndroidDriver.kt` → `hideKeyboard()`

**Server — Android**
- Logic: `maestro-android/MaestroDriverLib/src/main/kotlin/CommandRouter.kt`
- Glue:  `maestro-android/src/androidTest/.../MaestroDriverService.kt`

**Server — iOS**
- Logic: `maestro-ios-xctest-runner/MaestroDriverLib/Sources/.../PermissionButtonFinder.swift`
- Glue:  `maestro-ios-xctest-runner/maestro-driver-iosUITests/Routes/Handlers/`

**Tests to run**
- `./gradlew :maestro-android:MaestroDriverLib:test`
- `swift test` in `maestro-ios-xctest-runner/MaestroDriverLib/`
- Smoke test: `e2e/keyboard.yaml`
```

---

## Smoke Tests

Smoke tests are YAML flows in `e2e/` that run against a real device using the demo app. They are the regression gate for behaviour changes.

### When smoke tests run

- On every PR that touches `maestro-core`, `maestro-android/MaestroDriverLib`, or `maestro-ios-xctest-runner/MaestroDriverLib`
- Manually via `maestro test e2e/<skill>.yaml`

### What a smoke test looks like

```yaml
# e2e/keyboard.yaml
- launchApp: "dev.mobile.demoapp"
- tapOn: "Text Input"
- assertVisible: "keyboard"
- hideKeyboard
- assertNotVisible: "keyboard"
```

### Demo app

The demo app (`e2e/demoapp/`) is a minimal Android and iOS app that exercises each skill in isolation. It is the controlled surface for smoke tests — not a real third-party app whose UI can change.

---

## Testing Strategy

| Layer | Test type | Runs in CI without device |
|---|---|---|
| `maestro-core` | JUnit unit tests | Yes |
| `maestro-android/MaestroDriverLib` | JUnit unit tests | Yes |
| `MaestroDriverLib` (Swift) | `swift test` | Yes |
| `maestro-android` androidTest glue | Instrumentation tests | No — requires emulator |
| `maestro-ios-xctest-runner` UITest glue | XCUITest | No — requires simulator |
| `e2e/` smoke tests | Maestro flows | No — requires device/emulator |

The goal is to maximise the surface covered by the top three rows — pure logic that can be verified in CI on every commit without provisioning hardware.

---

## Performance

Once `MaestroDriverLib` is extracted and unit testable, benchmarks can be added alongside unit tests:

- `ViewHierarchySerializerTest` can measure serialization time for a 500-node tree
- `CommandRouterTest` can measure dispatch overhead

Specific known slow paths to investigate once the extraction is done:

- `viewHierarchy()` on Android blocks the main thread and does a full accessibility tree dump on every call — candidates for caching with invalidation on window change events
- `hideKeyboard` on iOS does a visibility poll loop — can be replaced with a single accessibility query

These are explicitly deferred until after the extraction, because profiling untestable code is not useful.

---

## Migration Steps

1. **Rename `maestro-client` → `maestro-core`** — update `settings.gradle.kts`, all `project()` references, and package names
2. **Merge `maestro-ios`, `maestro-ios-driver`, `maestro-web` into `maestro-core`** — move source files, update imports
3. **Create `maestro-android/MaestroDriverLib/`** — new nested Gradle subproject with `kotlin("jvm")`, extract `ViewHierarchySerializer` and `CommandRouter` from `androidTest`
4. **Write `AndroidBridge.kt`** — single file in `androidTest` that converts `AccessibilityNodeInfo` → `NodeInfo`
5. **Write unit tests** for `ViewHierarchySerializer` and `CommandRouter`
6. **Write `SKILLS.md`** — map every YAML command to its client and server files
7. **Write smoke tests** for each skill in `e2e/`
8. **Update CI** — add `MaestroDriverLib:test` and `swift test` gates, add smoke test job gated on device-capable runner