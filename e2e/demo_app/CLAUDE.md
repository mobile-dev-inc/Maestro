# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Is

A Flutter demo app used as the target app for end-to-end testing of the [Maestro](https://github.com/mobile-dev-inc/maestro) mobile UI testing framework. It is not a production app — its screens exist to exercise specific Maestro features and reproduce specific bugs.

When new screens or behaviors are needed to test a Maestro feature, they are added here.

## Build Commands

```sh
# Run the app (requires a connected device or emulator)
flutter run

# Build Android APK
flutter build apk

# Build iOS simulator app
flutter build ios --simulator

# Analyze code
flutter analyze
```

## Maestro Flow Commands

Flows live in `.maestro/`. **Prefer the Maestro MCP** for authoring, running, and debugging flows interactively (`list_devices` → `inspect_screen` / `take_screenshot` → `run`): it returns the view hierarchy and screenshots inline, which is far more effective for iterating than parsing CLI output. Use the CLI below for scripted or CI-style runs, or when no MCP is available.

```sh
# Run all flows
maestro test .maestro/

# Run a single flow
maestro test .maestro/fill_form.yaml

# Run flows with a specific tag
maestro test --include-tags passing .maestro/
```

**The MCP runs a *built* Maestro, not your working tree.** If you change Maestro framework code (anything outside `e2e/`) and want to validate it through the MCP against this app, the MCP will keep using the old build until Maestro is rebuilt **and the MCP is reconnected** (e.g. `/mcp reconnect maestro`). Rebuild, reconnect, then re-run. This is separate from rebuilding the demo app itself (see Build Commands) — a Dart/iOS/Android change to this app needs the app rebuilt and reinstalled on the device before the MCP will see it.

## Architecture

### Flutter App (`lib/`)

`main.dart` is the home screen with buttons navigating to each test screen. Each screen is a standalone Dart file targeting a specific testing scenario:

| File | Purpose |
|---|---|
| `form_screen.dart` | Login form with email/password validation |
| `input_screen.dart` | Keyboard and text input behaviors |
| `swiping_screen.dart` | Swipe gesture testing |
| `nesting_screen.dart` | Deeply nested widget hierarchies |
| `location_screen.dart` | GPS location via `geolocator`, streams position updates |
| `sensors_screen.dart` | Device sensors (Android only) |
| `webview.dart` | Embedded WebView via `webview_flutter` |
| `defects_screen.dart` | Intentional UI quirks for defect regression |
| `cropped_screenshot_screen.dart` | Screenshot cropping edge cases |
| `notifications_permission_screen.dart` | Permission request flows |
| `permission_check_screen.dart` | Passively displays permission status (location, all-files) via `permission_handler` — never calls `requestPermission()`, so it reflects a pre-granted state deterministically |
| `issue_1619_repro.dart`, `issue_1677_repro.dart` | Bug reproductions |

The app reads launch arguments via `flutter_launch_arguments` (e.g., `initialCounter`, `delay`) so Maestro flows can configure app state at launch.

### Maestro Flows (`.maestro/`)

- **Root flows** (`*.yaml`): Main passing/failing test cases, tagged `passing` or used to assert expected failures.
- **`commands/`**: Reusable Maestro command definitions (e.g., `assertVisible.yaml`, `inputText.yaml`).
- **`android_device_configuration/`** and **`ios_device_configuration/`**: Device setup flows run before tests (disable autocorrect, set timezone, enable sensors, etc.).
- **`web_flows/`**: Flows targeting web/WebView scenarios.
- **`issues/`**: Flows specifically reproducing reported Maestro bugs.
- **`experimental/`**: Unstable/in-progress flows not included in CI.
- **`scripts/`**: JavaScript helpers used by `evalScript` commands.

`config.yaml` configures which flow directories Maestro includes when running `maestro test .maestro/`.

**Platform targeting.** Write flows to run on both Android and iOS by default. Add an `android` or `ios` tag only when the behaviour is genuinely platform-specific — a flow with no platform tag runs on every platform. Prefer keeping a single cross-platform flow over splitting into per-platform files: use `${maestro.platform == "android" ? ... : ...}` for platform-specific values, and `runFlow` with `when: platform:` to guard platform-specific steps.

### App ID

All flows target `appId: com.example.example`.

## Testing Permissions

Non-obvious gotchas when writing permission flows against this app:

- **iOS: each `permission_handler` permission must be enabled in `ios/Podfile`.** A permission's handler is compiled in only when its macro is set in `GCC_PREPROCESSOR_DEFINITIONS` (e.g. `PERMISSION_LOCATION=1`). Without it, that permission's `.status` **silently returns denied on iOS** regardless of the real authorization. Enabled today: notifications, location. After editing the Podfile, run `pod install` in `ios/` before `flutter build ios` — a Podfile edit alone won't trigger it.
- **Android: runtime permissions must be declared in `AndroidManifest.xml`** or they can't be granted (`pm grant` throws). Declared today: INTERNET, ACCESS_FINE/COARSE_LOCATION, MANAGE_EXTERNAL_STORAGE. (So e.g. POST_NOTIFICATIONS can't be granted here.)
- **Permission values are platform-specific.** Android uses `allow`/`deny`/`unset`; iOS `location` uses `always`/`inuse`/`never`/`unset`. iOS *validates* location values and throws on anything else; Android silently falls back to revoke for unknown/empty values. For cross-platform flows, pick per platform: `location: ${maestro.platform == "android" ? "allow" : "always"}`.
- **`launchApp` with no `permissions:` block defaults to `all: allow`.**
- **Observe passively.** `permission_check_screen.dart` reads status without requesting — use it to assert a pre-granted state on both platforms. Avoid `location_screen.dart` (geolocator) for that: it actively calls `requestPermission()`, popping a dialog (Android does not auto-dismiss it) and resolving position asynchronously.
- **`MANAGE_EXTERNAL_STORAGE` is an appOps permission** on Android (special path), not a standard `pm grant` runtime permission.

## Adding New Test Screens

1. Create a new `lib/<feature>_screen.dart` with a `StatefulWidget`.
2. Add a navigation button in `lib/main.dart`.
3. Write the flow that exercises the **Maestro feature** you are testing, tagged `[passing]`. The flow is the point — the screen exists only to make that Maestro behaviour observable, not to be tested itself. Add a platform tag only if the behaviour is platform-specific (see **Platform targeting** above).
4. For location or sensor tests, ensure relevant device configuration flows exist in the platform-specific subdirectories.
