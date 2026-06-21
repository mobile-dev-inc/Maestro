# React Native Conformance Fixture

A third driver-conformance fixture (alongside `native` and `compose`): an **idiomatic React Native
0.86 app (New Architecture)** behavior-tested by the harness via `--framework react-native`.

## What it is

- Standalone RN app at `maestro-test/conformance-fixtures/react-native/` (app id
  `dev.mobile.maestro.fixture.rn`, New Arch on). Built into a **committed, arm64-v8a, ~18 MB release
  APK** with the JS bundle embedded → `maestro-test/src/main/resources/react-native-fixture.apk`.
  Rebuild with `build-rn-fixture.sh` (needs Node ≥ 20.12; the harness never needs Node at run time).
- Wired into `FixtureCatalog` as `react-native`. The compose-only `mergeDescendants` behavior is
  framework-scoped and skipped here automatically.

## Architecture

The harness oracle reads `MAESTRO_FIXTURE`-tagged logcat JSON. To keep that contract byte-identical
across all three fixtures:

- **`FixtureEmitter.kt`** is ported verbatim (same `{epoch, seq, event, …}` format).
- A **legacy `NativeModule`** (`FixtureEmitter`, exposed via `FixtureEmitterPackage`) bridges JS
  screens to the emitter — works under New Architecture through the bridgeless interop, no codegen.
- **Timing-critical lifecycle events are emitted natively** from a `ReactActivity` subclass
  (`MainActivity`) mirroring the native/compose `FixtureActivity`: SELFTEST, LIFECYCLE/LAUNCHED,
  DEEPLINK, MARK receiver, BACK, ORIENTATION. The launched `route` is passed to JS as an initial
  prop (`getLaunchOptions`), and the JS `Router` renders the matching screen.
- **Screens are idiomatic TSX**: `Pressable` (tap/longPress), `PanResponder` (swipe), `ScrollView`
  (scroll), `TextInput` + `Keyboard` listeners (input/keyboard), `Animated` (settle), `View`s with
  `testID`→resource-id / `accessibilityLabel`→content-desc (tree). AppLifecycle state persists in
  SharedPreferences natively so `clearAppState` has something to wipe.

## Findings (what the harness surfaced)

Building the fixture idiomatically and running it exposed real RN-vs-native differences. Per the
harness rule, each was fixed in the **harness or fixture wiring — never by faking a result**:

1. **First-launch cold start (dexopt) is slow for RN/Hermes.** The first command after install
   (`tap`) couldn't find its target within the settle budget, and `takeScreenshot` captured a blank
   pre-paint frame — while the same screen resolved fine on later commands. *Fix (harness, generic):*
   `ConformanceRunner.warmUp()` does one throwaway launch right after install, paying the one-time
   dexopt cost before the measured commands. This also stabilised `swipeDirection` and `killApp`.

2. **`singleTask` re-launch bypasses `onCreate`.** RN's template activity is `singleTask`; a fast
   re-launch of a still-alive process is delivered to `onNewIntent`, so `launchApp` never saw the
   LAUNCHED event. *Fix (fixture, idiomatic):* `MainActivity.onNewIntent` emits the launch events
   too — which is exactly how a real RN app handles deep links anyway.

3. **`pressKey` is idiomatic only for ENTER.** RN can't capture arbitrary hardware keyevents from
   JS, but ENTER arrives via `TextInput.onSubmitEditing`. The harness only presses ENTER, so this
   passes; other keys would need a native key listener (noted for future expansion).

No `AndroidDriver` change was needed for React Native.

## Result

```
api34-react-native   22/22 PASS
```

Verified on API 34 (arm64-v8a). All Tier-A commands pass on the committed slimmed APK:
tap, longPress, swipe*, scrollVertical, inputText, eraseText, pressKey, isKeyboardVisible,
hideKeyboard, contentDescriptor, waitUntilScreenIsStatic, waitForAppToSettle, launchApp, stopApp,
killApp, clearAppState, setOrientation, openLink, backPress, takeScreenshot.

## Rebuilding

```bash
maestro-test/conformance-fixtures/react-native/build-rn-fixture.sh   # builds + copies the APK
./gradlew :maestro-test:driverConformance --args="--api 34 --framework react-native"
```
