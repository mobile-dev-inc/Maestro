# Flutter Conformance Fixture

A fourth driver-conformance fixture (alongside `native`, `compose`, `react-native`): an idiomatic
**Flutter 3.38 app** behavior-tested by the harness via `--framework flutter`. **22/22 Tier-A
commands PASS on API 34.**

## The defining constraint (headline finding)

Flutter renders its entire UI to a **single `FlutterView` canvas** — there is **no native widget
hierarchy** in the Android accessibility / UiAutomator tree. The conformance harness (like Maestro
itself) resolves every element (`tap`, `swipe`, `inputText`, `contentDescriptor`, …) by
`resource-id` / `content-desc` in that tree. So for Flutter, **nothing is findable unless the app
opts in**:

1. **Semantics must be force-enabled.** Flutter only builds its semantics tree when an accessibility
   client is attached. The fixture calls `SemanticsBinding.instance.ensureSemantics()` at startup and
   holds the handle for the app's lifetime — otherwise the harness sees one empty `FlutterView`.
2. **Widgets must carry identifiers.** Each target is wrapped in
   `Semantics(identifier: 'tap_target', label: 'tap_target', …)` — `identifier` → Android
   `resource-id` (Flutter ≥ 3.19), `label` → `content-desc`.

This is the key takeaway for testing Flutter with Maestro: **enable semantics and annotate widgets**,
or no selector resolves. It's idiomatic-for-testability (the same thing you do for screen-reader
accessibility), so it lives in the fixture — not a harness/driver hack.

## What it is

- Standalone Flutter app at `maestro-test/conformance-fixtures/flutter/` (Android-only; `ios/` not
  generated). App id `dev.mobile.maestro.fixture.flutter`. Committed **arm64-v8a release APK, ~16 MB**
  → `maestro-test/src/main/resources/flutter-fixture.apk`; rebuilt by `build-flutter-fixture.sh`
  (needs the Flutter SDK; the harness never needs Flutter at run time). Wired into `FixtureCatalog`.

## Architecture

- **Oracle parity:** `FixtureEmitter.kt` ported verbatim (same `MAESTRO_FIXTURE` logcat contract).
  Dart screens emit per-screen events over a **`maestro.fixture/bridge` MethodChannel** → native →
  logcat. `seedState` and the launched `route` go over the same channel.
- **Native lifecycle:** a `FlutterActivity` subclass emits SELFTEST/LIFECYCLE/DEEPLINK/BACK/
  ORIENTATION and the MARK receiver — natively, so they don't depend on the Dart isolate. Dart pulls
  the route via `getRoute` and the `Router` renders the matching screen.
- **Idiomatic Dart screens:** `GestureDetector` (tap/longPress/swipe), `ListView` + scroll
  notifications, `TextField` + IME-inset detection, `AnimationController`, `Semantics`-wrapped tree.
  AppLifecycle state persists in SharedPreferences (native) so `clearAppState` has data to wipe.

## Findings (fixed in harness/fixture wiring — never faked)

1. **Force-enable semantics + identifiers** — the constraint above. Without it, every element command
   fails; with it, `contentDescriptor`/`tap`/`swipe`/`inputText`/… all resolve.
2. **Flutter cold start is heavy and was racy.** Each command stop-launches the app; Flutter's engine
   + Dart isolate + first frame + platform channel come up well after the activity, so event-timing
   commands (`tap`, `launchApp`, `openLink`, `waitUntilScreenIsStatic`, `takeScreenshot`) flaked
   against fixed short waits. Fixes, all generic and benefiting every framework:
   - `ConformanceRunner` now uses **framework-aware settle** (Flutter 3000 ms, RN 1800 ms, native/
     compose 1000 ms) plus a longer process-death wait for the heavy toolkits.
   - `TakeScreenshotBehavior` now **polls until the frame is non-blank** (and samples a 5×5 grid),
     instead of a single capture that could catch a pre-paint frame.
   - `OpenLinkBehavior` / `LaunchAppBehavior` lifecycle waits widened so the natively-emitted
     DEEPLINK/LAUNCHED aren't missed on a slow cold-start `onCreate`.
3. **`pressKey` is idiomatic only for ENTER** (via `TextField.onSubmitted`) — the only key the
   harness presses; arbitrary hardware keys aren't observable from a Flutter TextField. Same as RN.

No `AndroidDriver` change was needed for Flutter.

## Result

```
api34-flutter   22/22 PASS   (stable across repeated runs after the timing fixes)
```

## Rebuilding

```bash
maestro-test/conformance-fixtures/flutter/build-flutter-fixture.sh   # builds + copies the APK
./gradlew :maestro-test:driverConformance --args="--api 34 --framework flutter"
```
