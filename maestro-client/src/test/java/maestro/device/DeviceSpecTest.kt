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
    fun `Android defaults to the google_apis tag`() {
        val spec = DeviceSpec.Android(model = "pixel_6", os = "android-34")
        assertThat(spec.tag).isEqualTo(SystemImageTag.GOOGLE_APIS)
    }

    @Test
    fun `Android accepts a playstore tag`() {
        val spec = DeviceSpec.Android(
            model = "pixel_6",
            os = "android-34",
            tag = SystemImageTag.GOOGLE_APIS_PLAYSTORE,
        )
        assertThat(spec.tag).isEqualTo(SystemImageTag.GOOGLE_APIS_PLAYSTORE)
    }

    @Test
    fun `SystemImageTag fromImageTag maps a ps16k variant to its base family`() {
        assertThat(SystemImageTag.fromImageTag("google_apis_ps16k"))
            .isEqualTo(SystemImageTag.GOOGLE_APIS)
        assertThat(SystemImageTag.fromImageTag("google_apis_playstore_ps16k"))
            .isEqualTo(SystemImageTag.GOOGLE_APIS_PLAYSTORE)
    }

    @Test
    fun `SystemImageTag fromString rejects an unknown tag`() {
        assertThrows<IllegalArgumentException> { SystemImageTag.fromString("aosp_atd") }
    }

    @Test
    fun `Android computed osVersion is parsed from os string`() {
        val spec = DeviceSpec.Android(model = "pixel_6", os = "android-34")
        assertThat(spec.osVersion).isEqualTo(34)
    }

    @Test
    fun `Android osVersion parses the major level from a minor-versioned os`() {
        val spec = DeviceSpec.Android(model = "pixel_6", os = "android-37.1")
        assertThat(spec.osVersion).isEqualTo(37)
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
    fun `deviceName is suffixed with a non-default tag`() {
        val spec = DeviceSpec.Android(model = "pixel_6", os = "android-34",
            tag = SystemImageTag.GOOGLE_APIS_PLAYSTORE)
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
