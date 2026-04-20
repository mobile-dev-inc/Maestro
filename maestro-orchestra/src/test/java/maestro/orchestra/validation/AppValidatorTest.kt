package maestro.orchestra.validation

import com.google.common.truth.Truth.assertThat
import maestro.device.DeviceSpec
import maestro.device.Platform
import maestro.orchestra.validation.AndroidAppMetadata
import maestro.orchestra.validation.IosAppMetadata
import maestro.orchestra.validation.WebAppMetadata
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

class AppValidatorTest {

    private val androidResult = AndroidAppMetadata(
        name = "Example",
        packageId = "com.example.app",
        supportedArchitectures = listOf("arm64-v8a"),
        versionName = "1.0",
        versionCode = 1L,
    )
    private val iosResult = IosAppMetadata(
        name = "Example iOS",
        bundleId = "com.example.ios",
        platformName = "iphonesimulator",
        minimumOSVersion = "16.0",
        appVersion = "1.0",
        bundleVersion = "1",
    )
    private val webResult = WebAppMetadata(url = "https://example.com")

    @Test
    fun `validates local app file successfully`() {
        val appFile = File("app.apk")
        val validator = AppValidator(appFileValidator = { androidResult })

        val result = validator.validate(appFile = appFile, appBinaryId = null)

        assertThat(result).isEqualTo(androidResult)
    }

    @Test
    fun `throws UnrecognizedAppFile when local app file validation returns null`() {
        val validator = AppValidator(appFileValidator = { null })

        assertThrows<AppValidationException.UnrecognizedAppFile> {
            validator.validate(appFile = File("unknown.bin"), appBinaryId = null)
        }
    }

    @Test
    fun `validates app binary id from provider successfully`() {
        val validator = AppValidator(
            appFileValidator = { null },
            appBinaryInfoProvider = { id ->
                AppValidator.AppBinaryInfoResult(id, "Android", "com.example.app")
            },
        )

        val result = validator.validate(appFile = null, appBinaryId = "bin_123")

        assertThat(result.platform).isEqualTo(Platform.ANDROID)
        assertThat(result.appIdentifier).isEqualTo("com.example.app")
    }

    @Test
    fun `validates iOS app binary id from provider`() {
        val validator = AppValidator(
            appFileValidator = { null },
            appBinaryInfoProvider = { id ->
                AppValidator.AppBinaryInfoResult(id, "iOS", "com.example.ios")
            },
        )

        val result = validator.validate(appFile = null, appBinaryId = "bin_456")

        assertThat(result.platform).isEqualTo(Platform.IOS)
        assertThat(result.appIdentifier).isEqualTo("com.example.ios")
    }

    @Test
    fun `throws UnsupportedPlatform when provider returns unknown platform`() {
        val validator = AppValidator(
            appFileValidator = { null },
            appBinaryInfoProvider = { id ->
                AppValidator.AppBinaryInfoResult(id, "Symbian", "com.example.app")
            },
        )

        val error = assertThrows<AppValidationException.UnsupportedPlatform> {
            validator.validate(appFile = null, appBinaryId = "bin_bad")
        }
        assertThat(error.platform).isEqualTo("Symbian")
    }

    @Test
    fun `throws MissingAppSource when appBinaryId given but no provider`() {
        val validator = AppValidator(appFileValidator = { null })

        assertThrows<AppValidationException.MissingAppSource> {
            validator.validate(appFile = null, appBinaryId = "bin_123")
        }
    }

    @Test
    fun `validates web flow via manifest provider`() {
        val webManifest = File.createTempFile("manifest", ".json").also {
            it.writeText("""{"url": "https://example.com"}""")
            it.deleteOnExit()
        }

        val validator = AppValidator(
            appFileValidator = { webResult },
            webManifestProvider = { webManifest },
        )

        val result = validator.validate(appFile = null, appBinaryId = null)

        assertThat(result.platform).isEqualTo(Platform.WEB)
        assertThat(result.appIdentifier).isEqualTo("https://example.com")
    }

    @Test
    fun `throws MissingAppSource when no app file, no binary id, and no web manifest provider`() {
        val validator = AppValidator(appFileValidator = { null })

        assertThrows<AppValidationException.MissingAppSource> {
            validator.validate(appFile = null, appBinaryId = null)
        }
    }

    @Test
    fun `throws UnrecognizedAppFile when web manifest provider returns null`() {
        val validator = AppValidator(
            appFileValidator = { null },
            webManifestProvider = { null },
        )

        assertThrows<AppValidationException.UnrecognizedAppFile> {
            validator.validate(appFile = null, appBinaryId = null)
        }
    }

    // ---- validateDeviceCompatibility tests ----

    private fun iosDeviceSpec(os: String = "iOS-18-2"): DeviceSpec =
        DeviceSpec.Ios(model = "iPhone-11", os = os)

    private fun androidDeviceSpec(os: String = "android-33"): DeviceSpec =
        DeviceSpec.Android(model = "pixel_6", os = os)

    private fun webDeviceSpec(): DeviceSpec =
        DeviceSpec.Web(model = "chromium", os = "default")

    @Test
    fun `validateDeviceCompatibility passes when iOS app min version is below device version`() {
        val appFile = File("app.ipa")
        val validator = AppValidator(
            appFileValidator = { iosResult },
            iosMinOSVersionProvider = { AppValidator.IosMinOSVersion(major = 16, full = "16.0") },
        )

        validator.validateDeviceCompatibility(
            appFile = appFile,
            deviceSpec = iosDeviceSpec("iOS-18-2"),
        )
    }

    @Test
    fun `validateDeviceCompatibility passes when iOS app min version equals device version`() {
        val appFile = File("app.ipa")
        val validator = AppValidator(
            appFileValidator = { iosResult },
            iosMinOSVersionProvider = { AppValidator.IosMinOSVersion(major = 18, full = "18.0") },
        )

        validator.validateDeviceCompatibility(
            appFile = appFile,
            deviceSpec = iosDeviceSpec("iOS-18-2"),
        )
    }

    @Test
    fun `validateDeviceCompatibility throws IncompatibleIOSVersion when app requires higher OS`() {
        val appFile = File("app.ipa")
        val validator = AppValidator(
            appFileValidator = { iosResult },
            iosMinOSVersionProvider = { AppValidator.IosMinOSVersion(major = 18, full = "18.0") },
        )

        val error = assertThrows<AppValidationException.IncompatibleIOSVersion> {
            validator.validateDeviceCompatibility(
                appFile = appFile,
                deviceSpec = iosDeviceSpec("iOS-16-2"),
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

        // Should not throw even though min version (99) > device version (18)
        validator.validateDeviceCompatibility(
            appFile = null,
            deviceSpec = iosDeviceSpec("iOS-18-2"),
        )
    }

    @Test
    fun `validateDeviceCompatibility skips iOS check when provider returns null`() {
        val validator = AppValidator(
            appFileValidator = { iosResult },
            iosMinOSVersionProvider = { null },
        )

        // Should not throw — provider can't extract min OS version from this binary
        validator.validateDeviceCompatibility(
            appFile = File("app.ipa"),
            deviceSpec = iosDeviceSpec("iOS-16-2"),
        )
    }

    @Test
    fun `validateDeviceCompatibility skips iOS check when no provider injected`() {
        val validator = AppValidator(appFileValidator = { iosResult })

        // Should not throw — no iosMinOSVersionProvider
        validator.validateDeviceCompatibility(
            appFile = File("app.ipa"),
            deviceSpec = iosDeviceSpec("iOS-16-2"),
        )
    }

    @Test
    fun `validateDeviceCompatibility is a no-op for Android`() {
        val validator = AppValidator(appFileValidator = { androidResult })

        // Should not throw — Android API level validation is handled by DeviceSpecValidator
        validator.validateDeviceCompatibility(
            appFile = File("app.apk"),
            deviceSpec = androidDeviceSpec("android-33"),
        )
    }

    @Test
    fun `validateDeviceCompatibility is a no-op for Web`() {
        val validator = AppValidator(appFileValidator = { webResult })

        // Should not throw for any configuration
        validator.validateDeviceCompatibility(
            appFile = null,
            deviceSpec = webDeviceSpec(),
        )
    }
}
