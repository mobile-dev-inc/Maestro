# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Is

A Flutter demo app used as the target app for end-to-end testing of the [Maestro](https://github.com/mobile-dev-inc/maestro) mobile UI testing framework. It is not a production app — its screens exist to exercise specific Maestro features and reproduce specific bugs.

The built binaries are uploaded to a GCS bucket and downloaded by Maestro's E2E pipeline at test time. When new screens or behaviors are needed to test a Maestro feature, they are added here.

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

Flows live in `.maestro/` and are run against the app with the Maestro CLI:

```sh
# Run all flows
maestro test .maestro/

# Run a single flow
maestro test .maestro/fill_form.yaml

# Run flows with a specific tag
maestro test --include-tags passing .maestro/
```

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

### App ID

All flows target `appId: com.example.example`.

## Adding New Test Screens

1. Create a new `lib/<feature>_screen.dart` with a `StatefulWidget`.
2. Add a navigation button in `lib/main.dart`.
3. Create a corresponding `.maestro/<feature>.yaml` flow with `tags: [passing]`.
4. For location or sensor tests, ensure relevant device configuration flows exist in the platform-specific subdirectories.
