# Compose Fixture — Driver Conformance

## What was added

### Module: `maestro-test/conformance-fixtures/compose/`
- **`build.gradle.kts`** — AGP 8.13.2, Kotlin 2.2.0, Compose compiler plugin `org.jetbrains.kotlin.plugin.compose` 2.2.0, Compose BOM 2024.09.00, `copyComposeFixture` task → `maestro-test/src/main/resources/compose-fixture.apk`
- **`AndroidManifest.xml`** — `dev.mobile.maestro.fixture.compose` appId, full class name `dev.mobile.maestro.fixture.FixtureActivity`, same `configChanges` and `windowSoftInputMode` as native, `maestrofixture://` intent-filter
- **Shared infrastructure** (copied verbatim from native, same package `dev.mobile.maestro.fixture`): `FixtureEmitter.kt`, `MarkReceiver.kt`, `ReceiverRegistrar.kt`
- **`FixtureActivity.kt`** — extends `ComponentActivity`, same SELFTEST/LAUNCHED/DEEPLINK/MARK/onBackPressed/onConfigurationChanged logic; uses `setContent { Router(route) }` instead of `setContentView`
- **`Router.kt`** — `@Composable fun Router(route: String)` dispatching to 9 screen composables
- **9 screen composables** in `screens/`: `TapScreen`, `SwipeScreen`, `ScrollScreen`, `InputScreen`, `KeyboardScreen`, `TreeScreen`, `OrientationScreen`, `AnimationScreen`, `AppLifecycleScreen`

### Wiring changes
- **`settings.gradle.kts`** — added `include(":maestro-test:conformance-fixtures:compose")`
- **`maestro-test/src/main/kotlin/maestro/conformance/fixture/FixtureApp.kt`** — added `val compose = FixtureApp("compose", "dev.mobile.maestro.fixture.compose", "/compose-fixture.apk")` and `"compose" -> compose` case in `byName`
- **`maestro-test/build.gradle.kts`** — `driverConformance` task `dependsOn(":maestro-test:conformance-fixtures:compose:copyComposeFixture")`
- **`.gitignore`** — added `maestro-test/src/main/resources/compose-fixture.apk`

## Contract Validation

**Does contentDescriptor resolve Compose semantics? YES.**

Element IDs are exposed via `Modifier.semantics { contentDescription = "element_id" }` on each interactive element. This surfaces as `content-desc` in the UiAutomator accessibility hierarchy, which `TreeBounds.find` matches against. Confirmed with `uiautomator dump` — `tap_target`, `longpress_target`, `swipe_surface`, `scroll_container`, `text_field`, `tree_root`, `tree_label_a`, `tree_button_b`, `animate_button`, `state_seed_button` all appear in the hierarchy.

**Two implementation fixes required during iteration:**

1. **Long-press** — `detectTapGestures(onPress)` does not fire for UiAutomator `longPress()`. Fixed by using raw `awaitPointerEventScope` with `Press`/`Release` event types to measure hold duration.

2. **pressKey / KeyboardScreen** — Compose `onKeyEvent` modifier does not reliably intercept hardware key events sent by UiAutomator. Fixed by using `AndroidView(EditText)` with `setOnKeyListener` — same approach as native fixture.

## Results: api34-compose (API 34, emulator-5554)

| Command | Verdict |
|---|---|
| tap | PASS |
| takeScreenshot | PASS |
| longPress | PASS |
| swipeStartEnd | PASS |
| swipeDirection | PASS |
| swipeElement | PASS |
| scrollVertical | PASS |
| inputText | PASS |
| eraseText | PASS |
| pressKey | PASS |
| isKeyboardVisible | PASS |
| hideKeyboard | PASS |
| contentDescriptor | PASS |
| waitUntilScreenIsStatic | PASS |
| waitForAppToSettle | PASS |
| launchApp | PASS |
| stopApp | PASS |
| killApp | PASS |
| clearAppState | PASS |
| setOrientation | PASS |
| openLink | PASS |
| backPress | PASS |

**Total: 22/22 PASS** (zero reds)

## How to run

```bash
# Build compose APK and run all commands against a connected device:
./gradlew :maestro-test:driverConformance --args="--device <serial> --framework compose --record on-failure --out /tmp/compose34"

# Or use a fresh provisioned emulator:
./gradlew :maestro-test:driverConformance --args="--api 34 --framework compose --record on-failure --out /tmp/compose34"

# Single command (e.g. tap):
./gradlew :maestro-test:driverConformance --args="--device <serial> --framework compose --command tap --record all --out /tmp/compose34"
```

## Verification

Run output (summary.json):
```json
{
  "banner": "device: emulator-5554 (api 34) [user-supplied]",
  "total": 22,
  "passed": 22,
  "failed": 0,
  "apiFailed": 0,
  "cells": {
    "api34-compose": {
      "backPress": "PASS", "clearAppState": "PASS", "contentDescriptor": "PASS",
      "eraseText": "PASS", "hideKeyboard": "PASS", "inputText": "PASS",
      "isKeyboardVisible": "PASS", "killApp": "PASS", "launchApp": "PASS",
      "longPress": "PASS", "openLink": "PASS", "pressKey": "PASS",
      "scrollVertical": "PASS", "setOrientation": "PASS", "stopApp": "PASS",
      "swipeDirection": "PASS", "swipeElement": "PASS", "swipeStartEnd": "PASS",
      "takeScreenshot": "PASS", "tap": "PASS", "waitForAppToSettle": "PASS",
      "waitUntilScreenIsStatic": "PASS"
    }
  }
}
```
