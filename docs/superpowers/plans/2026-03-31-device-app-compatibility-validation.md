# Device & App Compatibility Validation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Validate device configuration and app compatibility in the CLI before upload, catching iOS min OS version mismatches, unsupported Android API levels, and invalid device model/OS combinations.

**Architecture:** Three new components: `DeviceSpecValidator` (CLI layer) resolves and validates device model+OS against the `v2/device/list` API; `AppValidator.validateDeviceCompatibility()` checks app binary against the validated device spec; new exception types surface clear error messages. All wired into `CloudInteractor.upload()` between `DeviceSpec.fromRequest()` and `WorkspaceValidator.validate()`.

**Tech Stack:** Kotlin, JUnit 5, Google Truth assertions, MockK (CLI tests only)

---

## File Map

| File | Action | Responsibility |
|------|--------|---------------|
| `maestro-orchestra/src/main/java/maestro/orchestra/validation/AppValidationException.kt` | Modify | Add `IncompatibleiOSVersion` and `UnsupportedAndroidApiLevel` |
| `maestro-cli/src/main/java/maestro/cli/cloud/DeviceSpecValidator.kt` | Create | Validate & resolve DeviceSpec against supported device list |
| `maestro-cli/src/test/kotlin/maestro/cli/cloud/DeviceSpecValidatorTest.kt` | Create | Tests for DeviceSpecValidator |
| `maestro-orchestra/src/main/java/maestro/orchestra/validation/AppValidator.kt` | Modify | Add `validateDeviceCompatibility()` method |
| `maestro-orchestra/src/test/java/maestro/orchestra/validation/AppValidatorTest.kt` | Modify | Tests for `validateDeviceCompatibility()` |
| `maestro-cli/src/main/java/maestro/cli/cloud/CloudInteractor.kt` | Modify | Wire new validation steps into upload flow |
| `maestro-cli/src/test/kotlin/maestro/cli/cloud/CloudInteractorTest.kt` | Modify | Integration tests for new validation in upload flow |

---

### Task 1: Add new exception types to AppValidationException

**Files:**
- Modify: `maestro-orchestra/src/main/java/maestro/orchestra/validation/AppValidationException.kt`

- [ ] **Step 1: Add IncompatibleiOSVersion and UnsupportedAndroidApiLevel exception classes**

Add these two new subclasses at the end of the sealed class:

```kotlin
class IncompatibleiOSVersion(val appMinVersion: String, val deviceOsVersion: Int) : AppValidationException(
    "App requires iOS $appMinVersion but device is configured for iOS $deviceOsVersion. " +
    "Set --device-os to a compatible version."
)

class UnsupportedAndroidApiLevel(val apiLevel: Int, val supported: List<String>) : AppValidationException(
    "Android API level $apiLevel is not supported. " +
    "Supported versions: ${supported.joinToString(", ")}"
)
```

The full file should look like:

```kotlin
package maestro.orchestra.validation

sealed class AppValidationException(message: String) : RuntimeException(message) {
    class MissingAppSource : AppValidationException("Missing required parameter for option '--app-file' or '--app-binary-id'")
    class UnrecognizedAppFile : AppValidationException("Could not determine platform. Provide a valid --app-file or --app-binary-id.")
    class AppBinaryNotFound(val appBinaryId: String) : AppValidationException("App binary '$appBinaryId' not found. Check your --app-binary-id.")
    class UnsupportedPlatform(val platform: String) : AppValidationException("Unsupported platform '$platform' returned by server. Please update your CLI.")
    class AppBinaryFetchError(val statusCode: Int?) : AppValidationException("Failed to fetch app binary info. Status code: $statusCode")
    class IncompatibleiOSVersion(val appMinVersion: String, val deviceOsVersion: Int) : AppValidationException(
        "App requires iOS $appMinVersion but device is configured for iOS $deviceOsVersion. " +
        "Set --device-os to a compatible version."
    )
    class UnsupportedAndroidApiLevel(val apiLevel: Int, val supported: List<String>) : AppValidationException(
        "Android API level $apiLevel is not supported. " +
        "Supported versions: ${supported.joinToString(", ")}"
    )
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :maestro-orchestra:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add maestro-orchestra/src/main/java/maestro/orchestra/validation/AppValidationException.kt
git commit -m "feat: add IncompatibleiOSVersion and UnsupportedAndroidApiLevel exceptions"
```

---

### Task 2: Create DeviceSpecValidator with TDD

**Files:**
- Create: `maestro-cli/src/main/java/maestro/cli/cloud/DeviceSpecValidator.kt`
- Create: `maestro-cli/src/test/kotlin/maestro/cli/cloud/DeviceSpecValidatorTest.kt`

The `DeviceSpecValidator` object validates and resolves a `DeviceSpec` against the supported device list from `v2/device/list`. It has its own `InvalidDeviceConfiguration` exception class.

The `supportedDevices` parameter is `Map<String, Map<String, List<String>>>` — platform key (lowercase) → model → list of OS version IDs. Example:

```kotlin
mapOf(
    "android" to mapOf("pixel_6" to listOf("android-34", "android-33", "android-30")),
    "ios" to mapOf(
        "iPhone-14" to listOf("iOS-16-2", "iOS-17-5", "iOS-18-2"),
        "iPhone-16-Pro" to listOf("iOS-18-2"),
    ),
    "web" to mapOf("chromium" to listOf("default")),
)
```

- [ ] **Step 1: Write failing tests**

Create the test file:

```kotlin
package maestro.cli.cloud

import com.google.common.truth.Truth.assertThat
import maestro.device.DeviceSpec
import maestro.device.DeviceSpecRequest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DeviceSpecValidatorTest {

    private val supportedDevices = mapOf(
        "android" to mapOf(
            "pixel_6" to listOf("android-34", "android-33", "android-31", "android-30", "android-29"),
        ),
        "ios" to mapOf(
            "iPhone-14" to listOf("iOS-16-2", "iOS-16-4", "iOS-17-5", "iOS-18-2"),
            "iPhone-16-Pro" to listOf("iOS-18-2"),
            "iPad-10th-generation" to listOf("iOS-16-2", "iOS-17-5", "iOS-18-2"),
        ),
        "web" to mapOf(
            "chromium" to listOf("default"),
        ),
    )

    // ---- Model resolution ----

    @Test
    fun `resolves exact Android model`() {
        val spec = DeviceSpec.fromRequest(DeviceSpecRequest.Android(model = "pixel_6", os = "android-33"))
        val result = DeviceSpecValidator.validate(spec, supportedDevices)
        assertThat(result.model).isEqualTo("pixel_6")
    }

    @Test
    fun `resolves iOS model case-insensitively`() {
        val spec = DeviceSpec.fromRequest(DeviceSpecRequest.Ios(model = "iphone-14", os = "iOS-18-2"))
        val result = DeviceSpecValidator.validate(spec, supportedDevices)
        assertThat(result.model).isEqualTo("iPhone-14")
    }

    @Test
    fun `resolves model with underscore-to-hyphen fallback`() {
        val spec = DeviceSpec.fromRequest(DeviceSpecRequest.Ios(model = "iPhone_16_Pro", os = "iOS-18-2"))
        val result = DeviceSpecValidator.validate(spec, supportedDevices)
        assertThat(result.model).isEqualTo("iPhone-16-Pro")
    }

    @Test
    fun `throws InvalidDeviceConfiguration for unsupported model`() {
        val spec = DeviceSpec.fromRequest(DeviceSpecRequest.Android(model = "galaxy_s21", os = "android-33"))
        val error = assertThrows<DeviceSpecValidator.InvalidDeviceConfiguration> {
            DeviceSpecValidator.validate(spec, supportedDevices)
        }
        assertThat(error.message).contains("galaxy_s21")
        assertThat(error.message).contains("not supported")
    }

    // ---- Android OS resolution ----

    @Test
    fun `resolves exact Android OS`() {
        val spec = DeviceSpec.fromRequest(DeviceSpecRequest.Android(model = "pixel_6", os = "android-34"))
        val result = DeviceSpecValidator.validate(spec, supportedDevices)
        assertThat(result.os).isEqualTo("android-34")
    }

    @Test
    fun `resolves Android OS shorthand - bare number`() {
        val spec = DeviceSpec.fromRequest(DeviceSpecRequest.Android(model = "pixel_6", os = "34"))
        val result = DeviceSpecValidator.validate(spec, supportedDevices)
        assertThat(result.os).isEqualTo("android-34")
    }

    @Test
    fun `throws InvalidDeviceConfiguration for unsupported Android OS`() {
        val spec = DeviceSpec.fromRequest(DeviceSpecRequest.Android(model = "pixel_6", os = "android-28"))
        val error = assertThrows<DeviceSpecValidator.InvalidDeviceConfiguration> {
            DeviceSpecValidator.validate(spec, supportedDevices)
        }
        assertThat(error.message).contains("android-28")
        assertThat(error.message).contains("not supported")
    }

    // ---- iOS OS resolution ----

    @Test
    fun `resolves exact iOS OS`() {
        val spec = DeviceSpec.fromRequest(DeviceSpecRequest.Ios(model = "iPhone-14", os = "iOS-18-2"))
        val result = DeviceSpecValidator.validate(spec, supportedDevices)
        assertThat(result.os).isEqualTo("iOS-18-2")
    }

    @Test
    fun `resolves iOS OS shorthand - major version only`() {
        val spec = DeviceSpec.fromRequest(DeviceSpecRequest.Ios(model = "iPhone-14", os = "18"))
        val result = DeviceSpecValidator.validate(spec, supportedDevices)
        assertThat(result.os).isEqualTo("iOS-18-2")
    }

    @Test
    fun `resolves iOS OS shorthand - prefix without minor`() {
        val spec = DeviceSpec.fromRequest(DeviceSpecRequest.Ios(model = "iPhone-14", os = "iOS-17"))
        val result = DeviceSpecValidator.validate(spec, supportedDevices)
        assertThat(result.os).isEqualTo("iOS-17-5")
    }

    @Test
    fun `throws InvalidDeviceConfiguration for unsupported iOS OS`() {
        val spec = DeviceSpec.fromRequest(DeviceSpecRequest.Ios(model = "iPhone-14", os = "iOS-15-0"))
        val error = assertThrows<DeviceSpecValidator.InvalidDeviceConfiguration> {
            DeviceSpecValidator.validate(spec, supportedDevices)
        }
        assertThat(error.message).contains("iOS-15-0")
        assertThat(error.message).contains("not supported")
    }

    @Test
    fun `throws InvalidDeviceConfiguration when iOS OS not available for specific model`() {
        // iPhone-16-Pro only supports iOS-18-2, not iOS-16-2
        val spec = DeviceSpec.fromRequest(DeviceSpecRequest.Ios(model = "iPhone-16-Pro", os = "iOS-16-2"))
        val error = assertThrows<DeviceSpecValidator.InvalidDeviceConfiguration> {
            DeviceSpecValidator.validate(spec, supportedDevices)
        }
        assertThat(error.message).contains("iOS-16-2")
        assertThat(error.message).contains("not supported")
    }

    // ---- Web ----

    @Test
    fun `resolves web device with exact match`() {
        val spec = DeviceSpec.fromRequest(DeviceSpecRequest.Web(model = "chromium", os = "default"))
        val result = DeviceSpecValidator.validate(spec, supportedDevices)
        assertThat(result.model).isEqualTo("chromium")
        assertThat(result.os).isEqualTo("default")
    }

    // ---- DeviceSpec copy preserves other fields ----

    @Test
    fun `validate preserves non-device fields on Android`() {
        val spec = DeviceSpec.fromRequest(DeviceSpecRequest.Android(
            model = "pixel_6", os = "33", locale = "de_DE"
        ))
        val result = DeviceSpecValidator.validate(spec, supportedDevices)
        assertThat(result.os).isEqualTo("android-33")
        assertThat(result.locale.toString()).isEqualTo("de_DE")
    }

    @Test
    fun `validate preserves non-device fields on iOS`() {
        val spec = DeviceSpec.fromRequest(DeviceSpecRequest.Ios(
            model = "iphone-14", os = "18", locale = "ja_JP"
        ))
        val result = DeviceSpecValidator.validate(spec, supportedDevices)
        assertThat(result.os).isEqualTo("iOS-18-2")
        assertThat(result.model).isEqualTo("iPhone-14")
        assertThat(result.locale.toString()).isEqualTo("ja_JP")
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :maestro-cli:test --tests "maestro.cli.cloud.DeviceSpecValidatorTest" --no-build-cache`
Expected: Compilation error — `DeviceSpecValidator` does not exist yet.

- [ ] **Step 3: Write DeviceSpecValidator implementation**

Create `maestro-cli/src/main/java/maestro/cli/cloud/DeviceSpecValidator.kt`:

```kotlin
package maestro.cli.cloud

import maestro.device.DeviceSpec

object DeviceSpecValidator {

    class InvalidDeviceConfiguration(message: String) : RuntimeException(message)

    fun validate(
        deviceSpec: DeviceSpec,
        supportedDevices: Map<String, Map<String, List<String>>>
    ): DeviceSpec {
        val platformKey = deviceSpec.platform.name.lowercase()
        val deviceMap = supportedDevices[platformKey]
            ?: throw InvalidDeviceConfiguration("Platform '$platformKey' not found in supported devices.")

        val resolvedModel = resolveModel(deviceSpec.model, platformKey, deviceMap)
        val candidates = deviceMap[resolvedModel] ?: emptyList()
        val resolvedOs = resolveOs(deviceSpec.os, platformKey, resolvedModel, candidates)

        return when (deviceSpec) {
            is DeviceSpec.Android -> deviceSpec.copy(model = resolvedModel, os = resolvedOs)
            is DeviceSpec.Ios -> deviceSpec.copy(model = resolvedModel, os = resolvedOs)
            is DeviceSpec.Web -> deviceSpec.copy(model = resolvedModel, os = resolvedOs)
        }
    }

    private fun resolveModel(
        model: String,
        platform: String,
        deviceMap: Map<String, List<String>>
    ): String {
        // Exact match (case-insensitive)
        deviceMap.keys.firstOrNull {
            it.equals(model, ignoreCase = true)
        }?.let { return it }

        // Backward compatibility: underscore to hyphen
        deviceMap.keys.firstOrNull {
            it.equals(model.replace('_', '-'), ignoreCase = true)
        }?.let { return it }

        throw InvalidDeviceConfiguration(
            "Device model '$model' is not supported for platform '$platform'. " +
            "Supported models: ${deviceMap.keys.joinToString(", ")}"
        )
    }

    private fun resolveOs(
        os: String,
        platform: String,
        resolvedModel: String,
        candidates: List<String>
    ): String {
        val resolved = when (platform) {
            "android" -> candidates.find {
                it == os || it == "android-$os"
            }
            "ios" -> candidates.find {
                it == os
                    || it.matches(Regex("(iOS|tvOS|watchOS)-$os-.*"))
                    || it.startsWith("$os-")
            }
            "web" -> candidates.find { it == os }
            else -> null
        }

        return resolved ?: throw InvalidDeviceConfiguration(
            "OS version '$os' is not supported for model '$resolvedModel', platform '$platform'. " +
            "Supported versions: ${candidates.joinToString(", ")}"
        )
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :maestro-cli:test --tests "maestro.cli.cloud.DeviceSpecValidatorTest" --no-build-cache`
Expected: All tests PASS.

- [ ] **Step 5: Commit**

```bash
git add maestro-cli/src/main/java/maestro/cli/cloud/DeviceSpecValidator.kt \
       maestro-cli/src/test/kotlin/maestro/cli/cloud/DeviceSpecValidatorTest.kt
git commit -m "feat: add DeviceSpecValidator to resolve and validate device model+OS against cloud device list"
```

---

### Task 3: Add validateDeviceCompatibility to AppValidator with TDD

**Files:**
- Modify: `maestro-orchestra/src/main/java/maestro/orchestra/validation/AppValidator.kt`
- Modify: `maestro-orchestra/src/test/java/maestro/orchestra/validation/AppValidatorTest.kt`

The `AppValidator` already takes an `appFileValidator` function via constructor. For `validateDeviceCompatibility`, we need iOS metadata extraction. To keep AppValidator testable and free of CLI-specific types, we inject the iOS metadata extractor the same way — as a function parameter. We add a new constructor parameter `iosMetadataProvider: (File) -> IosMinOSVersion?` where `IosMinOSVersion` is a simple data class. However, since `AppValidator` lives in `maestro-orchestra` and shouldn't depend on `AppMetadataAnalyzer` (which is in `maestro-cli`), we use the existing injection pattern: pass a lambda.

We'll add a new data class and a constructor parameter:

```kotlin
data class IosMinOSVersion(val major: Int, val full: String)
```

And the new constructor parameter:

```kotlin
private val iosMinOSVersionProvider: ((File) -> IosMinOSVersion?)? = null,
```

- [ ] **Step 1: Write failing tests**

Add these tests to `AppValidatorTest.kt`:

```kotlin
// Add import at top:
// import maestro.device.DeviceSpec
// import maestro.device.DeviceSpecRequest

// ---- validateDeviceCompatibility tests ----

@Test
fun `validateDeviceCompatibility passes when iOS app min version is below device version`() {
    val validator = AppValidator(
        appFileValidator = { iosResult },
        iosMinOSVersionProvider = { AppValidator.IosMinOSVersion(major = 16, full = "16.0") },
    )
    val deviceSpec = DeviceSpec.fromRequest(DeviceSpecRequest.Ios(os = "iOS-18-2"))
    val supportedDevices = mapOf(
        "ios" to mapOf("iPhone-11" to listOf("iOS-18-2")),
    )

    // Should not throw
    validator.validateDeviceCompatibility(
        appFile = File("test.app"),
        deviceSpec = deviceSpec,
        supportedDevices = supportedDevices,
    )
}

@Test
fun `validateDeviceCompatibility passes when iOS app min version equals device version`() {
    val validator = AppValidator(
        appFileValidator = { iosResult },
        iosMinOSVersionProvider = { AppValidator.IosMinOSVersion(major = 18, full = "18.0") },
    )
    val deviceSpec = DeviceSpec.fromRequest(DeviceSpecRequest.Ios(os = "iOS-18-2"))
    val supportedDevices = mapOf(
        "ios" to mapOf("iPhone-11" to listOf("iOS-18-2")),
    )

    // Should not throw
    validator.validateDeviceCompatibility(
        appFile = File("test.app"),
        deviceSpec = deviceSpec,
        supportedDevices = supportedDevices,
    )
}

@Test
fun `validateDeviceCompatibility throws IncompatibleiOSVersion when app requires higher OS`() {
    val validator = AppValidator(
        appFileValidator = { iosResult },
        iosMinOSVersionProvider = { AppValidator.IosMinOSVersion(major = 18, full = "18.0") },
    )
    val deviceSpec = DeviceSpec.fromRequest(DeviceSpecRequest.Ios(os = "iOS-16-2"))
    val supportedDevices = mapOf(
        "ios" to mapOf("iPhone-11" to listOf("iOS-16-2")),
    )

    val error = assertThrows<AppValidationException.IncompatibleiOSVersion> {
        validator.validateDeviceCompatibility(
            appFile = File("test.app"),
            deviceSpec = deviceSpec,
            supportedDevices = supportedDevices,
        )
    }
    assertThat(error.appMinVersion).isEqualTo("18.0")
    assertThat(error.deviceOsVersion).isEqualTo(16)
}

@Test
fun `validateDeviceCompatibility skips iOS check when appFile is null`() {
    val validator = AppValidator(
        appFileValidator = { iosResult },
        iosMinOSVersionProvider = { AppValidator.IosMinOSVersion(major = 99, full = "99.0") },
    )
    val deviceSpec = DeviceSpec.fromRequest(DeviceSpecRequest.Ios(os = "iOS-18-2"))
    val supportedDevices = mapOf(
        "ios" to mapOf("iPhone-11" to listOf("iOS-18-2")),
    )

    // Should not throw even though min version is 99 — no local file to check
    validator.validateDeviceCompatibility(
        appFile = null,
        deviceSpec = deviceSpec,
        supportedDevices = supportedDevices,
    )
}

@Test
fun `validateDeviceCompatibility passes for valid Android API level`() {
    val validator = AppValidator(appFileValidator = { androidResult })
    val deviceSpec = DeviceSpec.fromRequest(DeviceSpecRequest.Android(os = "android-33"))
    val supportedDevices = mapOf(
        "android" to mapOf("pixel_6" to listOf("android-34", "android-33", "android-30")),
    )

    // Should not throw
    validator.validateDeviceCompatibility(
        appFile = File("test.apk"),
        deviceSpec = deviceSpec,
        supportedDevices = supportedDevices,
    )
}

@Test
fun `validateDeviceCompatibility throws UnsupportedAndroidApiLevel for unsupported level`() {
    val validator = AppValidator(appFileValidator = { androidResult })
    val deviceSpec = DeviceSpec.fromRequest(DeviceSpecRequest.Android(os = "android-28"))
    val supportedDevices = mapOf(
        "android" to mapOf("pixel_6" to listOf("android-34", "android-33", "android-30")),
    )

    val error = assertThrows<AppValidationException.UnsupportedAndroidApiLevel> {
        validator.validateDeviceCompatibility(
            appFile = File("test.apk"),
            deviceSpec = deviceSpec,
            supportedDevices = supportedDevices,
        )
    }
    assertThat(error.apiLevel).isEqualTo(28)
    assertThat(error.supported).containsExactly("android-34", "android-33", "android-30")
}

@Test
fun `validateDeviceCompatibility is a no-op for Web`() {
    val validator = AppValidator(appFileValidator = { webResult })
    val deviceSpec = DeviceSpec.fromRequest(DeviceSpecRequest.Web())
    val supportedDevices = mapOf(
        "web" to mapOf("chromium" to listOf("default")),
    )

    // Should not throw
    validator.validateDeviceCompatibility(
        appFile = null,
        deviceSpec = deviceSpec,
        supportedDevices = supportedDevices,
    )
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :maestro-orchestra:test --tests "maestro.orchestra.validation.AppValidatorTest" --no-build-cache`
Expected: Compilation error — `validateDeviceCompatibility` does not exist, `IosMinOSVersion` does not exist.

- [ ] **Step 3: Implement validateDeviceCompatibility on AppValidator**

Modify `maestro-orchestra/src/main/java/maestro/orchestra/validation/AppValidator.kt` to:

```kotlin
package maestro.orchestra.validation

import maestro.device.AppValidationResult
import maestro.device.DeviceSpec
import maestro.device.Platform
import java.io.File

/**
 * Validates and resolves app metadata from a local file, a remote binary ID, or a web manifest.
 *
 * Dependencies are injected as functions so this class stays free of CLI/API-specific types.
 *
 * @param appFileValidator validates a local app file and returns its metadata, or null if unrecognized
 * @param appBinaryInfoProvider fetches app binary info from a remote server by binary ID. Returns a Triple of (appBinaryId, platform, appId).
 * @param webManifestProvider provides a web manifest file for web flows
 * @param iosMinOSVersionProvider extracts iOS minimum OS version from a local app file
 */
class AppValidator(
    private val appFileValidator: (File) -> AppValidationResult?,
    private val appBinaryInfoProvider: ((String) -> AppBinaryInfoResult)? = null,
    private val webManifestProvider: (() -> File?)? = null,
    private val iosMinOSVersionProvider: ((File) -> IosMinOSVersion?)? = null,
) {

    data class AppBinaryInfoResult(
        val appBinaryId: String,
        val platform: String,
        val appId: String,
    )

    data class IosMinOSVersion(val major: Int, val full: String)

    fun validate(appFile: File?, appBinaryId: String?): AppValidationResult {
        return when {
            appFile != null -> validateLocalAppFile(appFile)
            appBinaryId != null -> validateAppBinaryId(appBinaryId)
            webManifestProvider != null -> validateWebManifest()
            else -> throw AppValidationException.MissingAppSource()
        }
    }

    fun validateDeviceCompatibility(
        appFile: File?,
        deviceSpec: DeviceSpec,
        supportedDevices: Map<String, Map<String, List<String>>>
    ) {
        when (deviceSpec.platform) {
            Platform.IOS -> validateiOSCompatibility(appFile, deviceSpec)
            Platform.ANDROID -> validateAndroidCompatibility(deviceSpec, supportedDevices)
            Platform.WEB -> { /* no validation needed */ }
        }
    }

    private fun validateiOSCompatibility(appFile: File?, deviceSpec: DeviceSpec) {
        if (appFile == null) return
        val provider = iosMinOSVersionProvider ?: return
        val minOSVersion = provider(appFile) ?: return

        if (minOSVersion.major > deviceSpec.osVersion) {
            throw AppValidationException.IncompatibleiOSVersion(
                appMinVersion = minOSVersion.full,
                deviceOsVersion = deviceSpec.osVersion,
            )
        }
    }

    private fun validateAndroidCompatibility(
        deviceSpec: DeviceSpec,
        supportedDevices: Map<String, Map<String, List<String>>>
    ) {
        val androidDevices = supportedDevices["android"] ?: return
        val allSupportedOsVersions = androidDevices.values.flatten().distinct()

        if (deviceSpec.os !in allSupportedOsVersions) {
            throw AppValidationException.UnsupportedAndroidApiLevel(
                apiLevel = deviceSpec.osVersion,
                supported = allSupportedOsVersions,
            )
        }
    }

    private fun validateLocalAppFile(appFile: File): AppValidationResult {
        return appFileValidator(appFile)
            ?: throw AppValidationException.UnrecognizedAppFile()
    }

    private fun validateAppBinaryId(appBinaryId: String): AppValidationResult {
        val provider = appBinaryInfoProvider
            ?: throw AppValidationException.MissingAppSource()

        val info = provider(appBinaryId)

        val platform = try {
            Platform.fromString(info.platform)
        } catch (e: IllegalArgumentException) {
            throw AppValidationException.UnsupportedPlatform(info.platform)
        }

        return AppValidationResult(
            platform = platform,
            appIdentifier = info.appId,
        )
    }

    private fun validateWebManifest(): AppValidationResult {
        val manifest = webManifestProvider?.invoke()
        return manifest?.let { appFileValidator(it) }
            ?: throw AppValidationException.UnrecognizedAppFile()
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :maestro-orchestra:test --tests "maestro.orchestra.validation.AppValidatorTest" --no-build-cache`
Expected: All tests PASS (both existing and new).

- [ ] **Step 5: Commit**

```bash
git add maestro-orchestra/src/main/java/maestro/orchestra/validation/AppValidator.kt \
       maestro-orchestra/src/test/java/maestro/orchestra/validation/AppValidatorTest.kt
git commit -m "feat: add validateDeviceCompatibility to AppValidator for iOS min OS and Android API level checks"
```

---

### Task 4: Wire validation into CloudInteractor with TDD

**Files:**
- Modify: `maestro-cli/src/main/java/maestro/cli/cloud/CloudInteractor.kt`
- Modify: `maestro-cli/src/test/kotlin/maestro/cli/cloud/CloudInteractorTest.kt`

This task wires up `DeviceSpecValidator.validate()` and `AppValidator.validateDeviceCompatibility()` into the `CloudInteractor.upload()` flow.

**Key changes to CloudInteractor.upload():**

1. After constructing `appValidator` (line 137-149), add the `iosMinOSVersionProvider` parameter
2. After constructing `deviceSpec` (line 158-174), fetch supported devices, validate device spec, then validate device compatibility
3. Add a catch block for `DeviceSpecValidator.InvalidDeviceConfiguration`

- [ ] **Step 1: Write failing tests**

Add these tests to `CloudInteractorTest.kt`:

```kotlin
// ---- Device spec validation tests ----

@Test
fun `upload throws CliError when device model is not supported`() {
    every { mockApiClient.listCloudDevices() } returns mapOf(
        "android" to mapOf("pixel_6" to listOf("android-34", "android-33")),
        "ios" to mapOf("iPhone-14" to listOf("iOS-18-2")),
        "web" to mapOf("chromium" to listOf("default")),
    )

    val error = assertThrows<CliError> {
        createCloudInteractor().upload(
            flowFile = iosFlowFile(),
            appFile = iosApp(),
            async = true,
            projectId = "proj_1",
            deviceModel = "galaxy_s21",
        )
    }

    assertThat(error.message).contains("not supported")
    assertThat(error.message).contains("galaxy_s21")
}

@Test
fun `upload throws CliError when OS version is not supported for device`() {
    every { mockApiClient.listCloudDevices() } returns mapOf(
        "android" to mapOf("pixel_6" to listOf("android-34", "android-33")),
        "ios" to mapOf("iPhone-14" to listOf("iOS-18-2")),
        "web" to mapOf("chromium" to listOf("default")),
    )

    val error = assertThrows<CliError> {
        createCloudInteractor().upload(
            flowFile = iosFlowFile(),
            appFile = iosApp(),
            async = true,
            projectId = "proj_1",
            deviceOs = "iOS-15-0",
        )
    }

    assertThat(error.message).contains("not supported")
}

@Test
fun `upload with valid device config and compatible app succeeds`() {
    stubUploadResponse(platform = "IOS")
    every { mockApiClient.listCloudDevices() } returns mapOf(
        "android" to mapOf("pixel_6" to listOf("android-34", "android-33")),
        "ios" to mapOf("iPhone-11" to listOf("iOS-18-2")),
        "web" to mapOf("chromium" to listOf("default")),
    )

    val result = createCloudInteractor().upload(
        flowFile = iosFlowFile(),
        appFile = iosApp(),
        async = true,
        projectId = "proj_1",
    )

    assertThat(result).isEqualTo(0)
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :maestro-cli:test --tests "maestro.cli.cloud.CloudInteractorTest" --no-build-cache`
Expected: New tests fail — `listCloudDevices()` is not being called, `DeviceSpecValidator` is not wired in.

- [ ] **Step 3: Update CloudInteractor to wire in validation**

Modify `CloudInteractor.kt`. The changes are:

**a) Update the appValidator construction (around line 137) to include iosMinOSVersionProvider:**

Replace the `appValidator` construction block (lines 137-149) with:

```kotlin
            val appValidator = AppValidator(
                appFileValidator = appFileValidator,
                appBinaryInfoProvider = { binaryId ->
                    try {
                        val info = client.getAppBinaryInfo(authToken, binaryId)
                        AppValidator.AppBinaryInfoResult(info.appBinaryId, info.platform, info.appId)
                    } catch (e: ApiClient.ApiException) {
                        if (e.statusCode == 404) throw AppValidationException.AppBinaryNotFound(binaryId)
                        throw AppValidationException.AppBinaryFetchError(e.statusCode)
                    }
                },
                webManifestProvider = webManifestProvider,
                iosMinOSVersionProvider = { file ->
                    val metadata = maestro.cli.util.AppMetadataAnalyzer.getIosAppMetadata(file) ?: return@AppValidator null
                    val major = metadata.minimumOSVersion.substringBefore(".").toIntOrNull() ?: return@AppValidator null
                    AppValidator.IosMinOSVersion(major = major, full = metadata.minimumOSVersion)
                },
            )
```

**b) After the deviceSpec construction (after line 174), add validation:**

Insert after the `val deviceSpec: DeviceSpec = when ...` block and before the workspace validation block:

```kotlin
            // Fetch supported devices and validate device spec
            val supportedDevices = try {
                client.listCloudDevices()
            } catch (e: ApiClient.ApiException) {
                throw CliError("Failed to fetch supported devices. Status code: ${e.statusCode}")
            }

            val validatedDeviceSpec = try {
                DeviceSpecValidator.validate(deviceSpec, supportedDevices)
            } catch (e: DeviceSpecValidator.InvalidDeviceConfiguration) {
                throw CliError(e.message ?: "Invalid device configuration")
            }

            // Validate app-device compatibility
            try {
                appValidator.validateDeviceCompatibility(appFileToSend, validatedDeviceSpec, supportedDevices)
            } catch (e: AppValidationException) {
                throw CliError(e.message ?: "App-device compatibility check failed")
            }
```

- [ ] **Step 4: Update existing tests to stub listCloudDevices**

All existing `CloudInteractorTest` tests that call `upload()` need `listCloudDevices()` stubbed. Add this to the `setUp()` method after the existing stubs:

```kotlin
every { mockApiClient.listCloudDevices() } returns mapOf(
    "android" to mapOf("pixel_6" to listOf("android-34", "android-33", "android-31", "android-30", "android-29")),
    "ios" to mapOf(
        "iPhone-11" to listOf("iOS-16-2", "iOS-17-5", "iOS-18-2"),
        "iPhone-14" to listOf("iOS-16-2", "iOS-17-5", "iOS-18-2"),
    ),
    "web" to mapOf("chromium" to listOf("default")),
)
```

This ensures existing tests continue passing — the default device models (`pixel_6`, `iPhone-11`, `chromium`) and OS versions (`android-33`, `iOS-18-2`, `default`) are all in the stubbed list.

- [ ] **Step 5: Run all tests to verify they pass**

Run: `./gradlew :maestro-cli:test --tests "maestro.cli.cloud.CloudInteractorTest" --no-build-cache`
Expected: All tests PASS (both existing and new).

- [ ] **Step 6: Run the full test suite to check for regressions**

Run: `./gradlew :maestro-cli:test :maestro-orchestra:test --no-build-cache`
Expected: All tests PASS.

- [ ] **Step 7: Commit**

```bash
git add maestro-cli/src/main/java/maestro/cli/cloud/CloudInteractor.kt \
       maestro-cli/src/test/kotlin/maestro/cli/cloud/CloudInteractorTest.kt
git commit -m "feat: wire DeviceSpecValidator and app-device compatibility checks into CloudInteractor upload flow"
```
