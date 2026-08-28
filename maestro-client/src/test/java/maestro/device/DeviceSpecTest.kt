package maestro.device

import com.google.common.truth.Truth.assertThat
import maestro.device.locale.AndroidLocale
import maestro.device.locale.LocaleValidationException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class DeviceSpecTest {

    @Test
    fun `Android with only required fields uses defaults`() {
        val spec = DeviceSpec.Android(model = "pixel_6", os = "android-33")

        assertThat(spec.platform).isEqualTo(Platform.ANDROID)
        assertThat(spec.model).isEqualTo("pixel_6")
        assertThat(spec.os).isEqualTo("android-33")
        assertThat(spec.locale.code).isEqualTo("en_US")
        assertThat(spec.cpuArchitecture).isEqualTo(CPU_ARCHITECTURE.ARM64)
    }

    @Test
    fun `iOS with only required fields uses defaults`() {
        val spec = DeviceSpec.Ios(model = "iPhone-11", os = "iOS-17-5")

        assertThat(spec.platform).isEqualTo(Platform.IOS)
        assertThat(spec.model).isEqualTo("iPhone-11")
        assertThat(spec.os).isEqualTo("iOS-17-5")
        assertThat(spec.locale.code).isEqualTo("en_US")
    }

    @Test
    fun `Web with only required fields uses defaults`() {
        val spec = DeviceSpec.Web(model = "chromium", os = "default")

        assertThat(spec.platform).isEqualTo(Platform.WEB)
        assertThat(spec.model).isEqualTo("chromium")
        assertThat(spec.os).isEqualTo("default")
        assertThat(spec.locale.code).isEqualTo("en_US")
    }

    @Test
    fun `Android with all fields overridden`() {
        val spec = DeviceSpec.Android(
            model = "pixel_xl",
            os = "android-33",
            locale = AndroidLocale.fromString("de_DE"),
            cpuArchitecture = CPU_ARCHITECTURE.ARM64,
        )

        assertThat(spec.model).isEqualTo("pixel_xl")
        assertThat(spec.os).isEqualTo("android-33")
        assertThat(spec.locale.languageCode).isEqualTo("de")
        assertThat(spec.locale.countryCode).isEqualTo("DE")
    }

    @Test
    fun `systemImage resolves to the override when set`() {
        val spec = DeviceSpec.Android(
            model = "pixel_6",
            os = "android-34",
            systemImageOverride = "system-images;android-34;google_apis_playstore;arm64-v8a",
        )
        assertThat(spec.systemImage).isEqualTo("system-images;android-34;google_apis_playstore;arm64-v8a")
    }

    @Test
    fun `systemImage resolves to the google_apis default when no override is set`() {
        val spec = DeviceSpec.Android(model = "pixel_6", os = "android-34")
        assertThat(spec.systemImage).isEqualTo("system-images;android-34;google_apis;arm64-v8a")
    }

    @Test
    fun `systemImageOverride with fewer than 4 segments throws`() {
        assertThrows<IllegalArgumentException> {
            DeviceSpec.Android(model = "pixel_6", os = "android-34",
                systemImageOverride = "system-images;android-34;google_apis")
        }
    }

    @Test
    fun `systemImageOverride not starting with system-images throws`() {
        assertThrows<IllegalArgumentException> {
            DeviceSpec.Android(model = "pixel_6", os = "android-34",
                systemImageOverride = "android-34;google_apis;arm64-v8a;extra")
        }
    }

    @Test
    fun `systemImageOverride with a mismatched os segment throws`() {
        val error = assertThrows<IllegalArgumentException> {
            DeviceSpec.Android(model = "pixel_6", os = "android-34",
                systemImageOverride = "system-images;android-33;google_apis;arm64-v8a")
        }
        assertThat(error).hasMessageThat().contains("android-34")
    }

    @Test
    fun `Android computed osVersion is parsed from os string`() {
        val spec = DeviceSpec.Android(model = "pixel_6", os = "android-34")
        assertThat(spec.osVersion).isEqualTo(34)
    }

    @Test
    fun `iOS computed osVersion is parsed from os string`() {
        val spec = DeviceSpec.Ios(model = "iPhone-11", os = "iOS-17-5")
        assertThat(spec.osVersion).isEqualTo(17)
    }

    @Test
    fun `deviceName has no suffix for the default google_apis tag`() {
        val spec = DeviceSpec.Android(model = "pixel_6", os = "android-34")
        assertThat(spec.deviceName).isEqualTo("Maestro_ANDROID_pixel_6_android-34")
    }

    @Test
    fun `deviceName is suffixed with a non-default tag from the override`() {
        val spec = DeviceSpec.Android(model = "pixel_6", os = "android-34",
            systemImageOverride = "system-images;android-34;google_apis_playstore;arm64-v8a")
        assertThat(spec.deviceName).isEqualTo("Maestro_ANDROID_pixel_6_android-34_google_apis_playstore")
    }

    @Test
    fun `blank model throws IllegalArgumentException`() {
        assertThrows<IllegalArgumentException> {
            DeviceSpec.Android(model = "", os = "android-33")
        }
    }

    @Test
    fun `blank os throws IllegalArgumentException`() {
        assertThrows<IllegalArgumentException> {
            DeviceSpec.Android(model = "pixel_6", os = "")
        }
    }

    @Test
    fun `invalid Android locale combination throws at locale construction time`() {
        assertThrows<LocaleValidationException> {
            AndroidLocale.fromString("ar_US")
        }
    }

    @Test
    fun `unsupported Android language code throws at locale construction time`() {
        assertThrows<LocaleValidationException> {
            AndroidLocale.fromString("xx_US")
        }
    }

    @Test
    fun `malformed Android locale missing country throws at locale construction time`() {
        assertThrows<LocaleValidationException> {
            AndroidLocale.fromString("en")
        }
    }
}
