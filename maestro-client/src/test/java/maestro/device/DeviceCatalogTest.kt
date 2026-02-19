package maestro.device

import com.google.common.truth.Truth.assertThat
import maestro.DeviceOrientation
import maestro.locale.DeviceLocale
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class DeviceCatalogTest {
    @Test
    fun `resolve Android with no overrides uses defaults`() {
        val config = DeviceCatalog.resolve(Platform.ANDROID)

        assertThat(config.platform).isEqualTo(Platform.ANDROID)
        assertThat(config.model).isEqualTo(DeviceCatalog.defaultModel(Platform.ANDROID))
        assertThat(config.os).isEqualTo(DeviceCatalog.defaultOs(Platform.ANDROID))
        assertThat(config.locale).isEqualTo(DeviceLocale.getDefault(Platform.ANDROID))
        assertThat(config.orientation).isEqualTo(DeviceOrientation.PORTRAIT)
    }

    @Test
    fun `resolve iOS with no overrides uses defaults`() {
        val config = DeviceCatalog.resolve(Platform.IOS)

        assertThat(config.platform).isEqualTo(Platform.IOS)
        assertThat(config.model).isEqualTo(DeviceCatalog.defaultModel(Platform.IOS))
        assertThat(config.os).isEqualTo(DeviceCatalog.defaultOs(Platform.IOS))
        assertThat(config.locale).isEqualTo(DeviceLocale.getDefault(Platform.IOS))
        assertThat(config.orientation).isEqualTo(DeviceOrientation.PORTRAIT)
    }

    @Test
    fun `resolve Web with no overrides uses defaults`() {
        val config = DeviceCatalog.resolve(Platform.WEB)

        assertThat(config.platform).isEqualTo(Platform.WEB)
        assertThat(config.model).isEqualTo(DeviceCatalog.defaultModel(Platform.WEB))
        assertThat(config.os).isEqualTo(DeviceCatalog.defaultOs(Platform.WEB))
        assertThat(config.locale).isEqualTo(DeviceLocale.getDefault(Platform.WEB))
        assertThat(config.orientation).isEqualTo(DeviceOrientation.PORTRAIT)
    }

    @Test
    fun `resolve uses explicit values when provided`() {
        val config = DeviceCatalog.resolve(
            platform = Platform.ANDROID,
            model = "pixel_xl",
            os = "31",
            locale = "de_DE",
            orientation = DeviceOrientation.LANDSCAPE_LEFT,
        )

        assertThat(config.model).isEqualTo("pixel_xl")
        assertThat(config.os).isEqualTo("31")
        assertThat(config.locale.languageCode).isEqualTo("de")
        assertThat(config.locale.countryCode).isEqualTo("DE")
        assertThat(config.orientation).isEqualTo(DeviceOrientation.LANDSCAPE_LEFT)
    }

    @Test
    fun `resolve throws for unsupported Android model`() {
        val exception = assertThrows<CloudCompatibilityException> {
            DeviceCatalog.resolve(Platform.ANDROID, model = "galaxy_s21")
        }

        assertThat(exception.message).contains("galaxy_s21")
        assertThat(exception.message).contains("not available in the cloud")
        assertThat(exception.config.model).isEqualTo("galaxy_s21")
    }

    @Test
    fun `resolve throws for unsupported Android OS version`() {
        val exception = assertThrows<CloudCompatibilityException> {
            DeviceCatalog.resolve(Platform.ANDROID, model = "pixel_6", os = "99")
        }

        assertThat(exception.message).contains("99")
        assertThat(exception.message).contains("not supported")
    }

    @Test
    fun `resolve throws for unsupported iOS model`() {
        val exception = assertThrows<CloudCompatibilityException> {
            DeviceCatalog.resolve(Platform.IOS, model = "iPhone-99")
        }

        assertThat(exception.message).contains("iPhone-99")
    }

    @Test
    fun `resolve throws for unsupported iOS OS version`() {
        val exception = assertThrows<CloudCompatibilityException> {
            DeviceCatalog.resolve(Platform.IOS, model = "iPhone-11", os = "iOS-99-0")
        }

        assertThat(exception.message).contains("iOS-99-0")
    }

    @Test
    fun `resolve throws for unsupported Web model`() {
        val exception = assertThrows<CloudCompatibilityException> {
            DeviceCatalog.resolve(Platform.WEB, model = "firefox")
        }

        assertThat(exception.message).contains("firefox")
    }

    @Test
    fun `resolve succeeds for valid cloud-compatible configs`() {
        DeviceCatalog.resolve(Platform.ANDROID, model = "pixel_6", os = "34")
        DeviceCatalog.resolve(Platform.ANDROID, model = "pixel_xl", os = "30")
        DeviceCatalog.resolve(Platform.IOS, model = "iPhone-16-Pro-Max", os = "iOS-18-2")
        DeviceCatalog.resolve(Platform.IOS, model = "iPad-10th-generation", os = "iOS-17-0")
        DeviceCatalog.resolve(Platform.WEB, model = "chromium", os = "latest")
    }

    @Test
    fun `generateDeviceName should generate a new device name taking into account sharding`() {
        val andDroidConfig = DeviceCatalog.resolve(Platform.ANDROID)
        val iosConfig = DeviceCatalog.resolve(Platform.IOS, model = "iPhone-11", os = "iOS-18-2")

        assertThat(andDroidConfig.generateDeviceName()).isEqualTo("Maestro_ANDROID_pixel_6_30")
        assertThat(andDroidConfig.generateDeviceName(shardIndex = 1)).isEqualTo("Maestro_ANDROID_pixel_6_30_2")
        assertThat(iosConfig.generateDeviceName(shardIndex = 0)).isEqualTo("Maestro_IOS_iPhone-11_iOS-18-2_1")
        assertThat(iosConfig.generateDeviceName(shardIndex = 2)).isEqualTo("Maestro_IOS_iPhone-11_iOS-18-2_3")
    }
}
