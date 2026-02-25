package maestro.device

import com.google.common.truth.Truth.assertThat
import maestro.DeviceOrientation
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class DeviceCatalogTest {

    val cloudDevicesDummyData = SupportedDevicesResponse(
        ios = IosSupportedDevices(
            deviceCombinations = listOf(
                DeviceCombination("iPhone-11", "iOS-16-2"),
                DeviceCombination("iPhone-11", "iOS-17-5"),
                DeviceCombination("iPhone-11", "iOS-18-2"),
                DeviceCombination("iPhone-16", "iOS-18-2"),
            ),
            defaults = IosDefaults("iPhone-11", "iOS-17-5", "en_US", disableAnimations = true),
        ),
        android = AndroidSupportedDevices(
            deviceCombinations = listOf(
                DeviceCombination("pixel_6",  "system-images;android-34;google_apis;arm64-v8a"),
                DeviceCombination("pixel_6",  "system-images;android-33;google_apis;arm64-v8a"),
                DeviceCombination("pixel_xl", "system-images;android-34;google_apis;arm64-v8a"),
                DeviceCombination("pixel_xl", "system-images;android-33;google_apis;arm64-v8a"),
            ),
            defaults = AndroidDefaults(
                deviceModel = "pixel_6",
                deviceOs = "system-images;android-34;google_apis;arm64-v8a",
                locale = "en_US",
                disableAnimations = true,
                snapshotKeyHonorModalViews = false,
            ),
        ),
        web = WebSupportedDevices(
            deviceCombinations = listOf(DeviceCombination("chromium", "default")),
            defaults = WebDefaults("chromium", "default", "en_US"),
        ),
    )

    @BeforeEach
    fun setup() {
        DeviceCatalog.initForTest(cloudDevicesDummyData)
    }

    @Test
    fun `resolve Android with no overrides uses defaults`() {
        val config = DeviceCatalog.resolve(Platform.ANDROID) as MaestroDeviceConfiguration.Android

        assertThat(config.platform).isEqualTo(Platform.ANDROID)
        assertThat(config.deviceModel).isEqualTo(cloudDevicesDummyData.android.defaults.deviceModel)
        assertThat(config.emulatorImage).isEqualTo(cloudDevicesDummyData.android.defaults.deviceOs)
        assertThat(config.locale.code).isEqualTo(cloudDevicesDummyData.android.defaults.locale)
        assertThat(config.orientation).isEqualTo(DeviceOrientation.PORTRAIT)
        assertThat(config.disableAnimations).isEqualTo(cloudDevicesDummyData.android.defaults.disableAnimations)
        assertThat(config.snapshotKeyHonorModalViews).isEqualTo(cloudDevicesDummyData.android.defaults.snapshotKeyHonorModalViews)
    }

    @Test
    fun `resolve iOS with no overrides uses defaults`() {
        val config = DeviceCatalog.resolve(Platform.IOS) as MaestroDeviceConfiguration.Ios

        assertThat(config.platform).isEqualTo(Platform.IOS)
        assertThat(config.deviceModel).isEqualTo(cloudDevicesDummyData.ios.defaults.deviceModel)
        assertThat(config.deviceOs).isEqualTo(cloudDevicesDummyData.ios.defaults.deviceOs)
        assertThat(config.locale.code).isEqualTo(cloudDevicesDummyData.ios.defaults.locale)
        assertThat(config.orientation).isEqualTo(DeviceOrientation.PORTRAIT)
        assertThat(config.disableAnimations).isEqualTo(cloudDevicesDummyData.ios.defaults.disableAnimations)
    }

    @Test
    fun `resolve Web with no overrides uses defaults`() {
        val config = DeviceCatalog.resolve(Platform.WEB) as MaestroDeviceConfiguration.Web

        assertThat(config.platform).isEqualTo(Platform.WEB)
        assertThat(config.browser).isEqualTo(cloudDevicesDummyData.web.defaults.deviceModel)
    }

    @Test
    fun `resolve uses explicit values when provided`() {
        val config = DeviceCatalog.resolve(
            platform = Platform.ANDROID,
            model = "pixel_xl",
            os = "system-images;android-33;google_apis;arm64-v8a",
            locale = "de_DE",
            orientation = DeviceOrientation.LANDSCAPE_LEFT,
        ) as MaestroDeviceConfiguration.Android

        assertThat(config.deviceModel).isEqualTo("pixel_xl")
        assertThat(config.emulatorImage).isEqualTo("system-images;android-33;google_apis;arm64-v8a")
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
        assertThat((exception.config as MaestroDeviceConfiguration.Android).deviceModel).isEqualTo("galaxy_s21")
    }

    @Test
    fun `resolve throws for unsupported Android OS version`() {
        val exception = assertThrows<CloudCompatibilityException> {
            DeviceCatalog.resolve(Platform.ANDROID, model = "pixel_6", os = "system-images;android-99;google_apis;arm64-v8a")
        }

        assertThat(exception.message).contains("android-99")
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
    fun `resolve throws for unsupported Web browser`() {
        val exception = assertThrows<CloudCompatibilityException> {
            DeviceCatalog.resolve(Platform.WEB, model = "firefox")
        }

        assertThat(exception.message).contains("firefox")
    }

    @Test
    fun `resolve succeeds for valid cloud-compatible configs`() {
        DeviceCatalog.resolve(Platform.ANDROID, model = "pixel_6",  os = "system-images;android-34;google_apis;arm64-v8a")
        DeviceCatalog.resolve(Platform.ANDROID, model = "pixel_xl", os = "system-images;android-33;google_apis;arm64-v8a")
        DeviceCatalog.resolve(Platform.IOS,     model = "iPhone-11", os = "iOS-18-2")
        DeviceCatalog.resolve(Platform.IOS,     model = "iPhone-16", os = "iOS-18-2")
        DeviceCatalog.resolve(Platform.WEB,     model = "chromium")
    }

    @Test
    fun `generateDeviceName should generate a new device name taking into account sharding`() {
        val androidConfig = DeviceCatalog.resolve(Platform.ANDROID) as MaestroDeviceConfiguration.Android
        val iosConfig = DeviceCatalog.resolve(Platform.IOS, model = "iPhone-11", os = "iOS-18-2") as MaestroDeviceConfiguration.Ios

        assertThat(androidConfig.generateDeviceName()).isEqualTo("Maestro_ANDROID_pixel_6_system-images;android-34;google_apis;arm64-v8a")
        assertThat(androidConfig.generateDeviceName(shardIndex = 1)).isEqualTo("Maestro_ANDROID_pixel_6_system-images;android-34;google_apis;arm64-v8a_2")
        assertThat(iosConfig.generateDeviceName(shardIndex = 0)).isEqualTo("Maestro_IOS_iPhone-11_iOS-18-2_1")
        assertThat(iosConfig.generateDeviceName(shardIndex = 2)).isEqualTo("Maestro_IOS_iPhone-11_iOS-18-2_3")
    }
}
