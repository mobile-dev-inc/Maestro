package maestro.cli.cloud

import com.google.common.truth.Truth.assertThat
import maestro.device.DeviceSpec
import maestro.device.DeviceSpecRequest
import maestro.device.locale.DeviceLocale
import maestro.device.Platform
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
        val spec = DeviceSpec.fromRequest(DeviceSpecRequest.Android(model = "pixel_6", os = "android-34"))
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
        // "pixel_6" in supported devices, but user passes "pixel-6"
        val spec = DeviceSpec.fromRequest(DeviceSpecRequest.Android(model = "pixel-6", os = "android-34"))
        val result = DeviceSpecValidator.validate(spec, supportedDevices)
        assertThat(result.model).isEqualTo("pixel_6")
    }

    @Test
    fun `throws InvalidDeviceConfiguration for unsupported model`() {
        val spec = DeviceSpec.fromRequest(DeviceSpecRequest.Android(model = "galaxy_s21", os = "android-34"))
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
    fun `resolves Android OS shorthand - bare number to android-N`() {
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
    fun `resolves iOS OS shorthand - major version to first matching entry`() {
        // "18" should match first "iOS-18-*" entry which is "iOS-18-2"
        val spec = DeviceSpec.fromRequest(DeviceSpecRequest.Ios(model = "iPhone-14", os = "18"))
        val result = DeviceSpecValidator.validate(spec, supportedDevices)
        assertThat(result.os).isEqualTo("iOS-18-2")
    }

    @Test
    fun `resolves iOS OS shorthand - prefix without minor version`() {
        // "iOS-17" should match first entry starting with "iOS-17-" which is "iOS-17-5"
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
    fun `throws when iOS OS not available for specific model`() {
        // iPhone-16-Pro only supports iOS-18-2, not iOS-16-2
        val spec = DeviceSpec.fromRequest(DeviceSpecRequest.Ios(model = "iPhone-16-Pro", os = "iOS-16-2"))
        val error = assertThrows<DeviceSpecValidator.InvalidDeviceConfiguration> {
            DeviceSpecValidator.validate(spec, supportedDevices)
        }
        assertThat(error.message).contains("iOS-16-2")
        assertThat(error.message).contains("not supported")
    }

    // ---- Web device resolution ----

    @Test
    fun `resolves web device with exact match`() {
        val spec = DeviceSpec.fromRequest(DeviceSpecRequest.Web(model = "chromium", os = "default"))
        val result = DeviceSpecValidator.validate(spec, supportedDevices)
        assertThat(result.model).isEqualTo("chromium")
        assertThat(result.os).isEqualTo("default")
    }

    // ---- Field preservation ----

    @Test
    fun `validate preserves non-device fields on Android - locale`() {
        val spec = DeviceSpec.fromRequest(DeviceSpecRequest.Android(model = "pixel_6", os = "android-34", locale = "fr_FR"))
        val result = DeviceSpecValidator.validate(spec, supportedDevices)
        assertThat(result.locale).isEqualTo((spec as DeviceSpec.Android).locale)
    }

    @Test
    fun `validate preserves non-device fields on iOS - locale`() {
        val spec = DeviceSpec.fromRequest(DeviceSpecRequest.Ios(model = "iPhone-14", os = "iOS-18-2", locale = "de_DE"))
        val result = DeviceSpecValidator.validate(spec, supportedDevices)
        assertThat(result.locale).isEqualTo((spec as DeviceSpec.Ios).locale)
    }
}
