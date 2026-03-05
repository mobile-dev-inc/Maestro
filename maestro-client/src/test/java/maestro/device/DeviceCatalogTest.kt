package maestro.device

import com.google.common.truth.Truth.assertThat
import maestro.device.DeviceOrientation
import maestro.device.util.CPU_ARCHITECTURE
import maestro.device.locale.DeviceLocale
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class DeviceCatalogTest {

    val cloudDevicesDummyData = SupportedDevicesResponse(
        ios = PlatformSupportedDevices.Ios(
            deviceCombinations = listOf(
                MaestroDeviceConfiguration.Ios("iPhone-16-Pro", "iOS-18-2", DeviceLocale.fromString("en_US", Platform.IOS), DeviceOrientation.PORTRAIT, false),
                MaestroDeviceConfiguration.Ios("iPhone-16", "iOS-18-2", DeviceLocale.fromString("en_US", Platform.IOS), DeviceOrientation.PORTRAIT, false),
                MaestroDeviceConfiguration.Ios("iPhone-11", "iOS-17-5", DeviceLocale.fromString("en_US", Platform.IOS), DeviceOrientation.PORTRAIT, false),
                MaestroDeviceConfiguration.Ios("iPhone-11", "iOS-16-4", DeviceLocale.fromString("en_US", Platform.IOS), DeviceOrientation.PORTRAIT, false),
            ),
            defaults = MaestroDeviceConfiguration.Ios(
                deviceModel = "iPhone-16",
                deviceOs = "iOS-18-2",
                locale = DeviceLocale.fromString("en_US", Platform.IOS),
                orientation = DeviceOrientation.PORTRAIT,
                disableAnimations = true,
            ),
        ),
        android = PlatformSupportedDevices.Android(
            deviceCombinations = listOf(
                MaestroDeviceConfiguration.Android("pixel_6", "android-34", DeviceLocale.fromString("en_US", Platform.ANDROID), DeviceOrientation.PORTRAIT, false, false, CPU_ARCHITECTURE.ARM64),
                MaestroDeviceConfiguration.Android("pixel_6", "android-33", DeviceLocale.fromString("en_US", Platform.ANDROID), DeviceOrientation.PORTRAIT, false, false, CPU_ARCHITECTURE.ARM64),
                MaestroDeviceConfiguration.Android("pixel_6", "android-32", DeviceLocale.fromString("en_US", Platform.ANDROID), DeviceOrientation.PORTRAIT, false, false, CPU_ARCHITECTURE.ARM64),
                MaestroDeviceConfiguration.Android("pixel_6", "android-31", DeviceLocale.fromString("en_US", Platform.ANDROID), DeviceOrientation.PORTRAIT, false, false, CPU_ARCHITECTURE.ARM64),
                MaestroDeviceConfiguration.Android("pixel_6", "android-30", DeviceLocale.fromString("en_US", Platform.ANDROID), DeviceOrientation.PORTRAIT, false, false, CPU_ARCHITECTURE.ARM64),
            ),
            defaults = MaestroDeviceConfiguration.Android(
                deviceModel = "pixel_6",
                deviceOs = "android-34",
                locale = DeviceLocale.fromString("en_US", Platform.ANDROID),
                orientation = DeviceOrientation.PORTRAIT,
                disableAnimations = true,
                snapshotKeyHonorModalViews = false,
                cpuArchitecture = CPU_ARCHITECTURE.ARM64,
            ),
        ),
        web = PlatformSupportedDevices.Web(
            deviceCombinations = listOf(
                MaestroDeviceConfiguration.Web("chromium", "default"),
            ),
            defaults = MaestroDeviceConfiguration.Web(
                deviceModel = "chromium",
                deviceOs = "default",
            ),
        ),
    )

    @BeforeEach
    fun setup() {
        DeviceCatalog.initForTest(cloudDevicesDummyData)
    }

    @Test
    fun `resolve Android with no overrides uses defaults`() {
        val config = DeviceCatalog.resolve("android") as MaestroDeviceConfiguration.Android

        assertThat(config.platform).isEqualTo(Platform.ANDROID)
        assertThat(config.deviceModel).isEqualTo(cloudDevicesDummyData.android.defaults.deviceModel)
        assertThat(config.deviceOs).isEqualTo(cloudDevicesDummyData.android.defaults.deviceOs)
        assertThat(config.locale.code).isEqualTo(cloudDevicesDummyData.android.defaults.locale.code)
        assertThat(config.orientation).isEqualTo(DeviceOrientation.PORTRAIT)
        assertThat(config.disableAnimations).isEqualTo(cloudDevicesDummyData.android.defaults.disableAnimations)
        assertThat(config.snapshotKeyHonorModalViews).isEqualTo(cloudDevicesDummyData.android.defaults.snapshotKeyHonorModalViews)
    }

    @Test
    fun `resolve iOS with no overrides uses defaults`() {
        val config = DeviceCatalog.resolve("ios") as MaestroDeviceConfiguration.Ios

        assertThat(config.platform).isEqualTo(Platform.IOS)
        assertThat(config.deviceModel).isEqualTo(cloudDevicesDummyData.ios.defaults.deviceModel)
        assertThat(config.deviceOs).isEqualTo(cloudDevicesDummyData.ios.defaults.deviceOs)
        assertThat(config.locale.code).isEqualTo(cloudDevicesDummyData.ios.defaults.locale.code)
        assertThat(config.orientation).isEqualTo(DeviceOrientation.PORTRAIT)
        assertThat(config.disableAnimations).isEqualTo(cloudDevicesDummyData.ios.defaults.disableAnimations)
    }

    @Test
    fun `resolve Web with no overrides uses defaults`() {
        val config = DeviceCatalog.resolve("web") as MaestroDeviceConfiguration.Web

        assertThat(config.platform).isEqualTo(Platform.WEB)
        assertThat(config.deviceModel).isEqualTo(cloudDevicesDummyData.web.defaults.deviceModel)
        assertThat(config.deviceOs).isEqualTo(cloudDevicesDummyData.web.defaults.deviceOs)
    }

    @Test
    fun `resolve uses explicit values when provided`() {
        val config = DeviceCatalog.resolve(
            platform = "android",
            model = "pixel_xl",
            os = "android-33",
            locale = "de_DE",
            orientation = DeviceOrientation.LANDSCAPE_LEFT,
            systemArchitecture = CPU_ARCHITECTURE.ARM64,
        ) as MaestroDeviceConfiguration.Android

        assertThat(config.deviceModel).isEqualTo("pixel_xl")
        assertThat(config.deviceOs).isEqualTo("android-33")
        assertThat(config.emulatorImage).isEqualTo("system-images;android-33;google_apis;arm64-v8a")
        assertThat(config.locale.languageCode).isEqualTo("de")
        assertThat(config.locale.countryCode).isEqualTo("DE")
        assertThat(config.orientation).isEqualTo(DeviceOrientation.LANDSCAPE_LEFT)
    }

    @Test
    fun `resolve throws for unsupported Android`() {
        val wrongDeviceModel = DeviceCatalog.resolve("android", model = "galaxy_s21")
        val wrongDeviceOs = DeviceCatalog.resolve("android", model = "pixel_6", os = "android-99")

        val exceptionDevice = assertThrows<CloudCompatibilityException> {
            DeviceCatalog.checkCloudCompatibility(wrongDeviceModel)
        }
        val exceptionOs = assertThrows<CloudCompatibilityException> {
            DeviceCatalog.checkCloudCompatibility(wrongDeviceOs)
        }

        assertThat(exceptionDevice.message).contains("galaxy_s21")
        assertThat(exceptionDevice.message).contains("not available in the cloud")
        assertThat((exceptionDevice.config as MaestroDeviceConfiguration.Android).deviceModel).isEqualTo("galaxy_s21")
        assertThat(exceptionOs.message).contains("android-99")
        assertThat(exceptionOs.message).contains("not supported")
    }

    @Test
    fun `resolve throws for unsupported iOS`() {
        val wrongDeviceModel = DeviceCatalog.resolve("ios", model = "iPhone-99")
        val wrongDeviceOs = DeviceCatalog.resolve("ios", model = "iPhone-11", os = "iOS-99-0")

        val exceptionModel = assertThrows<CloudCompatibilityException> {
            DeviceCatalog.checkCloudCompatibility(wrongDeviceModel)
        }
        val exceptionOs = assertThrows<CloudCompatibilityException> {
            DeviceCatalog.checkCloudCompatibility(wrongDeviceOs)
        }

        assertThat(exceptionModel.message).contains("iPhone-99")
        assertThat(exceptionOs.message).contains("iOS-99-0")
    }

    @Test
    fun `checkCloudCompatibility throws for unsupported Web browser`() {
        val maestroConfig = DeviceCatalog.resolve("web", model = "firefox")

        val exception = assertThrows<CloudCompatibilityException> {
            DeviceCatalog.checkCloudCompatibility(maestroConfig)
        }

        assertThat(exception.message).contains("firefox")
    }

    @Test
    fun `resolve succeeds for valid cloud-compatible configs`() {
        DeviceCatalog.resolve("android", model = "pixel_6",  os = "android-34")
        DeviceCatalog.resolve("android", model = "pixel_xl", os = "android-33")
        DeviceCatalog.resolve("ios",     model = "iPhone-11", os = "iOS-18-2")
        DeviceCatalog.resolve("ios",     model = "iPhone-16", os = "iOS-18-2")
        DeviceCatalog.resolve("web",     model = "chromium")
    }
}
