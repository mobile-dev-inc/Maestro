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
            ),
            defaults = IosDefaults("iPhone-11", "iOS-17-5", "en_US", disableAnimations = true),
        ),
        android = AndroidSupportedDevices(
            deviceCombinations = listOf(
                DeviceCombination("pixel_6",  "android-34"),
                DeviceCombination("pixel_6",  "android-33"),
                DeviceCombination("pixel_xl", "android-34"),
            ),
            defaults = AndroidDefaults(
                deviceModel = "pixel_6",
                deviceOs = "android-34",
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
        val spec = DeviceCatalog.resolve("android") as DeviceSpec.Android

        assertThat(spec.platform).isEqualTo(Platform.ANDROID)
        assertThat(spec.model).isEqualTo("pixel_6")
        assertThat(spec.os).isEqualTo("android-34")
        assertThat(spec.locale.code).isEqualTo("en_US")
        assertThat(spec.orientation).isEqualTo(DeviceOrientation.PORTRAIT)
        assertThat(spec.disableAnimations).isEqualTo(false)
        assertThat(spec.snapshotKeyHonorModalViews).isEqualTo(false)
    }

    @Test
    fun `resolve iOS with no overrides uses defaults`() {
        val spec = DeviceCatalog.resolve("ios") as DeviceSpec.Ios

        assertThat(spec.platform).isEqualTo(Platform.IOS)
        assertThat(spec.model).isEqualTo("iPhone-11")
        assertThat(spec.os).isEqualTo("iOS-18-2")
        assertThat(spec.locale.code).isEqualTo("en_US")
        assertThat(spec.orientation).isEqualTo(DeviceOrientation.PORTRAIT)
        assertThat(spec.disableAnimations).isEqualTo(false)
    }

    @Test
    fun `resolve Web with no overrides uses defaults`() {
        val spec = DeviceCatalog.resolve("web") as DeviceSpec.Web

        assertThat(spec.platform).isEqualTo(Platform.WEB)
        assertThat(spec.model).isEqualTo("chromium")
        assertThat(spec.os).isEqualTo("default")
        assertThat(spec.locale.code).isEqualTo("en_US")
    }

    @Test
    fun `resolve uses explicit values when provided`() {
        val spec = DeviceCatalog.resolve(
            platform = "android",
            model = "pixel_xl",
            os = "android-33",
            locale = "de_DE",
            orientation = DeviceOrientation.LANDSCAPE_LEFT,
            systemArchitecture = CPU_ARCHITECTURE.ARM64,
        ) as DeviceSpec.Android

        assertThat(spec.model).isEqualTo("pixel_xl")
        assertThat(spec.os).isEqualTo("android-33")
        assertThat(spec.emulatorImage).isEqualTo("system-images;android-33;google_apis;arm64-v8a")
        assertThat(spec.locale.languageCode).isEqualTo("de")
        assertThat(spec.locale.countryCode).isEqualTo("DE")
        assertThat(spec.orientation).isEqualTo(DeviceOrientation.LANDSCAPE_LEFT)
    }

    @Test
    fun `resolve also update image when system architecture is different`() {
        val spec = DeviceCatalog.resolve(
            platform = "android",
            model = "pixel_xl",
            os = "android-33",
            locale = "de_DE",
            orientation = DeviceOrientation.LANDSCAPE_LEFT,
            systemArchitecture = CPU_ARCHITECTURE.X86_64,
        ) as DeviceSpec.Android

        assertThat(spec.emulatorImage).isEqualTo("system-images;android-33;google_apis;x86_64")
    }

    @Test
    fun `resolve throws on unsupported platform`() {
        assertThrows<IllegalArgumentException> {
            DeviceCatalog.resolve("windows")
        }
    }

    @Test
    fun `resolve Android throws on invalid locale combination like ar_US`() {
        // Arabic is a supported language, US is a supported country,
        // but ar_US is not a valid Java locale combination
        assertThrows<LocaleValidationException> {
            DeviceCatalog.resolve("android", locale = "ar_US")
        }
    }

    @Test
    fun `resolve Android throws on unsupported language code`() {
        assertThrows<LocaleValidationException> {
            DeviceCatalog.resolve("android", locale = "xx_US")
        }
    }

    @Test
    fun `resolve Android throws on malformed locale missing country`() {
        assertThrows<LocaleValidationException> {
            DeviceCatalog.resolve("android", locale = "en")
        }
    }
}
