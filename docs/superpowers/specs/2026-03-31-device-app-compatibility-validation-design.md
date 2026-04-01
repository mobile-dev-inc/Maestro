# Device & App Compatibility Validation — Design Spec

**Date:** 2026-03-31
**Status:** Approved

## Goal

Move device/OS validation from the copilot backend into the maestro CLI so that incompatible configurations are caught before upload. This enables faster feedback and prepares for removing these checks from the backend.

Two validations:
1. **iOS minimum OS version** — app's `MinimumOSVersion` must not exceed the configured device OS
2. **Android supported API level** — configured API level must exist in the supported device list

Additionally, the CLI will validate the `DeviceSpec` (model + OS) against the `v2/device/list` API before checking app compatibility.

Framework detection is explicitly out of scope (unused on backend today).

## Architecture

### Flow

```
AppValidator.validate()                    → platform + appId
DeviceSpec.fromRequest()                   → construct DeviceSpec with defaults
client.listCloudDevices()                  → fetch v2/device/list
DeviceSpecValidator.validate()             → resolve & validate model+OS against device list
AppValidator.validateDeviceCompatibility() → check app vs validated device
WorkspaceValidator.validate()              → check workspace flows
Upload
```

### Components

#### 1. DeviceSpecValidator (new)

**Location:** `maestro-cli/src/main/java/maestro/cli/cloud/DeviceSpecValidator.kt`

**Purpose:** Validates and resolves a `DeviceSpec` against the supported device list from `v2/device/list`. Replicates the backend's `SupportedDevices.validate()` logic.

```kotlin
object DeviceSpecValidator {
    fun validate(
        deviceSpec: DeviceSpec,
        supportedDevices: Map<String, Map<String, List<String>>>
    ): DeviceSpec
}
```

**Resolution rules (matching backend):**

- **Model resolution:**
  - Exact match (case-insensitive)
  - Fallback: `_` to `-` conversion (backward compat with older CLIs)
  - Throws `InvalidDeviceConfiguration` if no match

- **OS resolution per platform:**
  - **Android:** `"34"` matches `"android-34"`, or exact match
  - **iOS:** `"18"` matches first `"iOS-18-*"` entry; `"iOS-18"` matches `"iOS-18-2"`; or exact match
  - **Web:** exact match only
  - Throws `InvalidDeviceConfiguration` if no match

**Returns:** A new `DeviceSpec` copy with resolved `model` and `os` values.

#### 2. AppValidator.validateDeviceCompatibility() (new method)

**Location:** `maestro-orchestra/src/main/java/maestro/orchestra/validation/AppValidator.kt`

**Purpose:** Validates that an app binary is compatible with the resolved device configuration.

```kotlin
fun validateDeviceCompatibility(
    appFile: File?,
    deviceSpec: DeviceSpec,
    supportedDevices: Map<String, Map<String, List<String>>>
)
```

**Validation logic:**

- **iOS:** Extract `MinimumOSVersion` from app via `AppMetadataAnalyzer.getIosAppMetadata()`. Parse the major version. Compare against `deviceSpec.osVersion`. If app minimum > device version, throw `IncompatibleiOSVersion`.
- **Android:** Extract API level from `deviceSpec.os` (e.g., `"android-33"` → `33`). Check it exists in `supportedDevices["android"]` values. If not, throw `UnsupportedAndroidApiLevel`.
- **Web:** No validation, return.
- **appFile is null** (using `--app-binary-id`): Skip iOS min OS check (no local binary to inspect).

#### 3. New exception types

**Location:** `maestro-orchestra/src/main/java/maestro/orchestra/validation/AppValidationException.kt`

New exception subclasses:

**In `AppValidationException`** (app-related):

| Exception | Trigger | Message |
|-----------|---------|---------|
| `IncompatibleiOSVersion` | App min OS > device OS | `"App requires iOS X but device is configured for iOS Y. Set --device-os to a compatible version."` |
| `UnsupportedAndroidApiLevel` | API level not in supported list | `"Android API level X is not supported. Supported versions: ..."` |

**In `DeviceSpecValidator`** (device-related):

| Exception | Trigger | Message |
|-----------|---------|---------|
| `InvalidDeviceConfiguration` | Model not in supported list | `"Device model 'X' is not supported for platform 'Y'. Supported models: ..."` |
| `InvalidDeviceConfiguration` | OS not supported for model | `"OS version 'X' is not supported for model 'Y'. Supported versions: ..."` |

`InvalidDeviceConfiguration` extends `RuntimeException` and lives in the CLI layer alongside `DeviceSpecValidator`, since it is about cloud device resolution, not app validation.

#### 4. CloudInteractor changes

**Location:** `maestro-cli/src/main/java/maestro/cli/cloud/CloudInteractor.kt`

Changes to `upload()` method (after line 155, before workspace validation):

1. Call `client.listCloudDevices()` to get supported devices
2. Call `DeviceSpecValidator.validate(deviceSpec, supportedDevices)` — throws `InvalidDeviceConfiguration`
3. Call `appValidator.validateDeviceCompatibility(appFileToSend, validatedDeviceSpec, supportedDevices)` — throws `AppValidationException`

Both exception types are caught in `CloudInteractor` and converted to `CliError`. A new catch block is needed for `InvalidDeviceConfiguration`.

### Data Sources

- **Supported device list:** `v2/device/list` endpoint, already called by `ApiClient.listCloudDevices()`. Returns `Map<String, Map<String, List<String>>>` (platform → model → OS versions).
- **iOS MinimumOSVersion:** Extracted from Info.plist by `AppMetadataAnalyzer.getIosAppMetadata()`. Already implemented.
- **DeviceSpec.osVersion:** Already computed from `os` string (e.g., `"iOS-18-2"` → `18`, `"android-33"` → `33`).

### Error Handling

All new exceptions are `AppValidationException` subclasses. `CloudInteractor` already catches these and wraps them in `CliError`:

```kotlin
} catch (e: AppValidationException) {
    throw CliError(e.message ?: "App validation failed")
}
```

No new catch blocks needed — just extend the existing try/catch to cover the new calls.

## Out of Scope

- **Framework detection** — currently unused on the backend, not porting
- **Override flags** (e.g., `--skip-validation`) — all checks are hard blocks
- **APK minSdkVersion validation** — only checking API level exists in supported list
- **New API endpoints** — using existing `v2/device/list`

## Files Changed

| File | Change |
|------|--------|
| `maestro-cli/.../cloud/DeviceSpecValidator.kt` | New file |
| `maestro-orchestra/.../validation/AppValidator.kt` | Add `validateDeviceCompatibility()` |
| `maestro-orchestra/.../validation/AppValidationException.kt` | Add 3 new exception subclasses |
| `maestro-cli/.../cloud/CloudInteractor.kt` | Wire up new validation steps |
| `maestro-orchestra/.../.../AppValidatorTest.kt` | Tests for new validation |
| `maestro-cli/.../cloud/DeviceSpecValidatorTest.kt` | New test file |
| `maestro-cli/.../cloud/CloudInteractorTest.kt` | Update existing tests |
