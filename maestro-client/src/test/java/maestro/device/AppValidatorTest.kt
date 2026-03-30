package maestro.device

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class AppValidatorTest {

    // ---- Android ----

    @Test
    fun `android validation rejects APK without arm64-v8a architecture`() {
        assertThrows<IllegalArgumentException> {
            AppValidator.fromAndroidMetadata(
                packageId = "com.example.app",
                supportedArchitectures = listOf("x86_64"),
            )
        }
    }

    @Test
    fun `android validation accepts APK with arm64-v8a among architectures`() {
        val result = AppValidator.fromAndroidMetadata(
            packageId = "com.example.app",
            supportedArchitectures = listOf("arm64-v8a", "x86_64"),
        )
        assertEquals("com.example.app", result.appIdentifier)
    }

    @Test
    fun `android validation accepts APK with no native libraries (empty arch list)`() {
        // Pure Java/Kotlin APKs have no lib/ entries — arm64 check doesn't apply
        val result = AppValidator.fromAndroidMetadata(
            packageId = "com.example.app",
            supportedArchitectures = emptyList(),
        )
        assertEquals(Platform.ANDROID, result.platform)
    }

    // ---- iOS ----

    @Test
    fun `ios validation accepts simulator build with minimumOSVersion set`() {
        val result = AppValidator.fromIosMetadata(
            bundleId = "com.example.app",
            platformName = "iphonesimulator",
            minimumOSVersion = "16.0",
        )
        assertEquals("com.example.app", result.appIdentifier)
        assertEquals(Platform.IOS, result.platform)
    }

    @Test
    fun `ios validation rejects device build (iphoneos platform name)`() {
        assertThrows<IllegalArgumentException> {
            AppValidator.fromIosMetadata(
                bundleId = "com.example.app",
                platformName = "iphoneos",
                minimumOSVersion = "16.0",
            )
        }
    }

    @Test
    fun `ios validation rejects null platform name`() {
        assertThrows<IllegalArgumentException> {
            AppValidator.fromIosMetadata(
                bundleId = "com.example.app",
                platformName = null,
                minimumOSVersion = "16.0",
            )
        }
    }

    @Test
    fun `ios validation rejects null minimum OS version`() {
        assertThrows<IllegalArgumentException> {
            AppValidator.fromIosMetadata(
                bundleId = "com.example.app",
                platformName = "iphonesimulator",
                minimumOSVersion = null,
            )
        }
    }
}
