# Roku Platform Support for Maestro

## Overview

This fork adds Roku as a fourth platform to Maestro (alongside Android, iOS, Web). Roku devices are controlled over the network via the **External Control Protocol (ECP)** — an HTTP REST API on port 8060. The implementation is modeled after the [roku-test-automation](https://github.com/triwav/roku-test-automation) project.

Key architectural difference from mobile/web: Roku uses **D-pad focus navigation**, not touch. Test flows use `pressKey` for navigation rather than `tapOn`.

## Current Status

### What's Working (Phases 1, 2, 4 — Complete)

**Platform wiring** — `ROKU` is fully integrated into Maestro's platform abstraction:
- `Platform.ROKU` enum value
- `DeviceSpec.Roku` / `DeviceSpecRequest.Roku` sealed subclasses
- `RokuLocale` (starts with `en_US`)
- `Device.DeviceType.STREAMING_DEVICE`
- All exhaustive `when(platform)` statements updated across the codebase
- `Maestro.roku()` factory method
- `MaestroSessionManager.createRoku()` session creation

**RokuDriver** — Full `Driver` interface implementation (`maestro-client/src/main/java/maestro/drivers/RokuDriver.kt`):
- Device connection and info via ECP
- App launch/stop via ECP
- D-pad key input (all remote keys mapped)
- Text input via `LIT_` character-by-character ECP calls
- View hierarchy via `/query/app-ui` XML parsing
- Screenshots via two-step `plugin_inspect` + download (digest auth)
- Swipe/scroll translated to repeated D-pad presses
- Tap sends `Select` (activates focused element)

**Supporting classes:**
- `RokuEcpClient` (`maestro-roku/src/main/kotlin/maestro/roku/`) — OkHttp-based ECP HTTP client with retry logic
- `RokuAppUIParser` (`maestro-client/src/main/java/maestro/drivers/roku/`) — Parses app-ui XML into `TreeNode` tree with scene-relative bounding rects
- `RokuKeyMapping` (`maestro-client/src/main/java/maestro/drivers/roku/`) — Maps `KeyCode` enum to Roku ECP key strings
- `RokuDeviceDiscovery` (`maestro-roku/src/main/kotlin/maestro/roku/`) — Finds devices via `MAESTRO_ROKU_HOST` env var

**New KeyCodes** added: `REMOTE_INFO`, `REMOTE_REPLAY`, `REMOTE_SEARCH` (with Android key mappings).

**Build status:** Full project compiles (`./gradlew compileKotlin`) and all existing tests pass (`./gradlew test`) with zero regressions.

### What's Not Yet Implemented (Phases 3, 5)

#### Phase 3: Device Discovery & CLI Flags

1. **SSDP auto-discovery** — Multicast discovery on `239.255.255.250:1900` with `ST: roku:ecp` to find devices on the LAN without manual IP entry.

2. **`DeviceService.listRokuDevices()` wiring** — Returns empty list. Should call `RokuDeviceDiscovery` so `maestro list-devices` shows Roku devices and the interactive picker works.

#### Phase 5: Tests

1. **`RokuAppUIParser` unit tests** — Parse sample XML fixtures, verify TreeNode structure, bounding rect math, RowListItem/MarkupGrid edge cases.

2. **`RokuEcpClient` unit tests** — Use MockWebServer to mock ECP endpoints, verify retry logic, text input iteration, screenshot flow.

3. **`RokuDriver` unit tests** — Verify unsupported methods (no-op vs throw), verify delegation.

4. **Integration test** — Requires physical dev-mode Roku on the network.

#### Minor Gaps

- **`waitUntilScreenIsStatic`** — Currently sleeps instead of comparing screenshots in a loop.
- **`waitForAppToSettle`** — Currently sleeps instead of comparing view hierarchies.
- **Design resolution scaling** — Parser assumes 1920x1080; should query device's actual UI resolution and scale.
- **`LocaleUtils.kt:228`** — Non-exhaustive `when` works via `else` but could add explicit ROKU case.

## Architecture

### Module Structure

```
maestro-roku/                          # Pure Roku utilities (no maestro-client dependency)
  src/main/kotlin/maestro/roku/
    RokuEcpClient.kt                   # HTTP client for ECP (port 8060)
    RokuDeviceDiscovery.kt             # Device discovery via env vars / SSDP

maestro-client/                        # Core module (depends on maestro-roku)
  src/main/java/maestro/
    drivers/
      RokuDriver.kt                    # Driver interface implementation
      roku/
        RokuKeyMapping.kt              # KeyCode -> ECP key string mapping
        RokuAppUIParser.kt             # app-ui XML -> TreeNode conversion
    device/
      locale/RokuLocale.kt            # Roku locale enum
```

No circular dependencies: `maestro-roku` only depends on OkHttp/Okio/SLF4J. Classes needing Maestro types (`TreeNode`, `KeyCode`) live in `maestro-client`.

### Key Code Mapping (KeyCode -> Roku ECP)

| Maestro KeyCode | Roku ECP Key | Description |
|---|---|---|
| `REMOTE_UP` | `Up` | D-pad up |
| `REMOTE_DOWN` | `Down` | D-pad down |
| `REMOTE_LEFT` | `Left` | D-pad left |
| `REMOTE_RIGHT` | `Right` | D-pad right |
| `REMOTE_CENTER` / `ENTER` | `Select` | OK / activate |
| `BACK` | `Back` | Back button |
| `HOME` | `Home` | Home button |
| `REMOTE_PLAY_PAUSE` | `Play` | Play/pause toggle |
| `REMOTE_FAST_FORWARD` | `Fwd` | Fast forward |
| `REMOTE_REWIND` | `Rev` | Rewind |
| `REMOTE_INFO` | `Info` | Options / * button |
| `REMOTE_REPLAY` | `InstantReplay` | Instant replay |
| `REMOTE_SEARCH` | `Search` | Search |
| `BACKSPACE` | `Backspace` | Delete character |

### Files Modified (from upstream Maestro)

| File | Change |
|---|---|
| `settings.gradle.kts` | `include("maestro-roku")` |
| `maestro-client/build.gradle.kts` | `implementation(project(":maestro-roku"))` |
| `Platform.kt` | Added `ROKU("Roku")` |
| `Device.kt` | Added `STREAMING_DEVICE` to DeviceType |
| `DeviceSpec.kt` | Added `Roku` subclass, `DeviceSpecRequest.Roku`, Jackson annotation, `fromRequest` branch |
| `DeviceLocale.kt` | Added `Platform.ROKU` to all 5 `when` statements |
| `DeviceService.kt` | Added `ROKU` to `startDevice()`, added `listRokuDevices()`, wired into `listDevices()` |
| `KeyCode.kt` | Added `REMOTE_INFO`, `REMOTE_REPLAY`, `REMOTE_SEARCH` |
| `AndroidDriver.kt` | Added new KeyCode cases to `pressKey()` |
| `Maestro.kt` | Added `roku()` factory method |
| `MaestroSessionManager.kt` | Added `ROKU` branches + `createRoku()` |
| `PickDeviceInteractor.kt` | Added ROKU launch message |
| `PickDeviceView.kt` | Added ROKU to platform prompt and DeviceSpecRequest |
| `StartDeviceCommand.kt` | Added ROKU DeviceSpecRequest branch |
| `AppValidator.kt` | Added `Platform.ROKU -> return` |
| `DeviceCreateUtil.kt` | Added `DeviceSpec.Roku` branch |
| `App.kt` | Added `--roku-host` and `--roku-password` global CLI flags |
| `TestCommand.kt` | Passes `rokuHost`/`rokuPassword` to `newSession()` |
| `RecordCommand.kt` | Passes `rokuHost`/`rokuPassword` to `newSession()` |
| `StudioCommand.kt` | Passes `rokuHost`/`rokuPassword` to `newSession()` |
| `QueryCommand.kt` | Passes `rokuHost`/`rokuPassword` to `newSession()` |
| `PrintHierarchyCommand.kt` | Passes `rokuHost`/`rokuPassword` to `newSession()` |

## Building the CLI for Local Use

### Prerequisites

- Java 17 or newer (check with `java -version`)
- The Maestro source tree at this path

### Build options

**Option A — Local build, run from source tree:**

```bash
./gradlew :maestro-cli:installDist
```

This produces a runnable CLI at:

```
./maestro-cli/build/install/maestro/bin/maestro
```

You can run it directly or alias it:

```bash
alias maestro-dev="/path/to/Maestro/maestro-cli/build/install/maestro/bin/maestro"
maestro-dev --roku-host=192.168.1.100 test flow.yaml
```

**Option B — Install globally (replaces public Maestro):**

```bash
./installLocally.sh
```

This runs the same Gradle build, then copies the result into `~/.maestro/bin` and `~/.maestro/lib`, replacing whatever version was installed via `curl -Ls "https://get.maestro.mobile.dev" | bash`. After this, any `maestro` command on your PATH uses the fork.

To revert to the public release later, re-run the public install script.

**Option C — Use from another project without installing globally:**

In your Roku project, create a wrapper script that points to the local build:

```bash
#!/bin/bash
# scripts/maestro.sh
MAESTRO_BIN="/path/to/Maestro/maestro-cli/build/install/maestro/bin/maestro"
exec "$MAESTRO_BIN" "$@"
```

Then run flows with:

```bash
./scripts/maestro.sh --roku-host=192.168.1.100 test .maestro/
```

### Rebuilding after changes

After editing Maestro source, re-run `./gradlew :maestro-cli:installDist`. The build is incremental, so subsequent builds are fast (~5-10 seconds).

To verify everything compiles and tests pass:

```bash
./gradlew compileKotlin   # compile check
./gradlew test            # run all tests
```

## Usage

### Connecting to a Roku Device

**Device setup:**

1. The Roku must be in **developer mode**. To enable it, on the Roku remote press: Home 3x, Up 2x, Right, Left, Right, Left, Right. Set a developer password when prompted.

2. The device must be on the same network as the machine running Maestro.

3. **ECP network access must be set to Permissive.** Recent Roku OS updates restrict ECP commands by default (returns 403 Forbidden). On the Roku: Settings > System > Advanced system settings > Control by mobile apps > Network access > **Permissive**. Without this, key presses, screenshots, and text input will fail.

**Option 1 — CLI flags (preferred):**

```bash
maestro --roku-host=192.168.1.100 --roku-password=devpwd test flow.yaml
```

**Option 2 — Environment variables:**

```bash
export MAESTRO_ROKU_HOST=192.168.1.100
export MAESTRO_ROKU_PASSWORD=devpwd
maestro test flow.yaml
```

The `--roku-host` flag (or `MAESTRO_ROKU_HOST` env var) automatically selects the Roku platform — no need to also pass `--platform roku`. The password is only required for screenshots.

**Resolution order:** CLI flag > environment variable. If both are set, the flag wins.

### Writing Roku Test Flows

Roku flows use the same YAML syntax as other Maestro platforms. The key difference is that interaction happens through **D-pad key presses** rather than tap coordinates.

#### Available commands

| Command | Roku behavior |
|---|---|
| `launchApp` | Launches the channel via ECP `/launch/{appId}` |
| `pressKey: <key>` | Sends a remote key press (see key table below) |
| `inputText: "hello"` | Types text character-by-character via ECP `LIT_` |
| `assertVisible: "text"` | Checks the view hierarchy for visible text |
| `takeScreenshot: path.png` | Captures screenshot (requires dev password) |
| `back` | Sends the Back key |
| `scroll` | Sends repeated Down key presses |
| `swipe` | Translated to repeated directional key presses |

#### Key names for `pressKey`

```
Remote Dpad Up          Remote Dpad Down
Remote Dpad Left        Remote Dpad Right
Remote Dpad Center      (OK / Select)
Back                    Home
Remote Media Play Pause Remote Media Stop
Remote Media Next       Remote Media Previous
Remote Media Rewind     Remote Media Fast Forward
Remote Info             (Options / * button)
Remote Instant Replay
Remote Search
Backspace
Enter                   (same as Remote Dpad Center)
Volume Up               Volume Down
```

### Example Flows

#### Basic navigation

```yaml
appId: dev
---
- launchApp
- pressKey: Remote Dpad Down
- pressKey: Remote Dpad Down
- pressKey: Remote Dpad Center
- assertVisible: "Settings"
- takeScreenshot: screenshots/settings.png
- pressKey: Back
```

#### Text search

```yaml
appId: dev
---
- launchApp
- pressKey: Remote Search
- inputText: "breaking bad"
- pressKey: Remote Dpad Down
- pressKey: Remote Dpad Center
- assertVisible: "Breaking Bad"
- takeScreenshot: screenshots/search_result.png
```

#### Media playback

```yaml
appId: dev
---
- launchApp
- pressKey: Remote Dpad Center
- assertVisible: "Now Playing"
- pressKey: Remote Media Play Pause
- takeScreenshot: screenshots/playback_paused.png
- pressKey: Remote Media Play Pause
- pressKey: Remote Media Fast Forward
- pressKey: Back
```

#### Full remote navigation

```yaml
appId: dev
---
- launchApp
# Navigate grid: right 2, down 1
- pressKey: Remote Dpad Right
- pressKey: Remote Dpad Right
- pressKey: Remote Dpad Down
# Select the item
- pressKey: Remote Dpad Center
# Use the options menu
- pressKey: Remote Info
- assertVisible: "Add to Favorites"
- pressKey: Remote Dpad Center
- pressKey: Back
- pressKey: Home
```

### Project integration

A typical Roku project using Maestro for testing:

```
my-roku-app/
├── source/                 # BrightScript source
├── components/             # SceneGraph components
├── manifest
├── .maestro/               # Maestro test flows
│   ├── launch.yaml
│   ├── navigation.yaml
│   ├── playback.yaml
│   └── search.yaml
└── scripts/
    └── test.sh
```

Where `scripts/test.sh`:

```bash
#!/bin/bash
ROKU_IP="${ROKU_IP:-192.168.1.100}"
ROKU_PWD="${ROKU_PWD:-devpassword}"

# Deploy the app (via roku-deploy or your build tool)
# ...

# Run Maestro tests
maestro --roku-host="$ROKU_IP" --roku-password="$ROKU_PWD" test .maestro/
```

## Reference

- **roku-test-automation project**: `../roku-test-automation` — TypeScript framework this implementation is modeled after
- **Roku ECP documentation**: https://developer.roku.com/docs/developer-program/dev-tools/external-control-api.md
- **Plan file**: `.claude/plans/rosy-hatching-kay.md`
