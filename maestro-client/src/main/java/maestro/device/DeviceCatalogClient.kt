package maestro.device

import maestro.DeviceOrientation
import maestro.device.util.CPU_ARCHITECTURE
import maestro.locale.DeviceLocale
//import com.fasterxml.jackson.databind.DeserializationFeature
//import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
//import maestro.utils.HttpClient
//import okhttp3.Request

// --- Response types ---

data class SupportedDevicesResponse(
    val ios: PlatformSupportedDevices.Ios,
    val android: PlatformSupportedDevices.Android,
    val web: PlatformSupportedDevices.Web,
) {
    fun forPlatform(platform: Platform): PlatformSupportedDevices = when (platform) {
        Platform.IOS     -> ios
        Platform.ANDROID -> android
        Platform.WEB     -> web
    }
}

sealed class PlatformSupportedDevices {
    abstract val deviceCombinations: List<MaestroDeviceConfiguration>
    abstract val defaults: MaestroDeviceConfiguration

    data class Android(
        override val deviceCombinations: List<MaestroDeviceConfiguration.Android>,
        override val defaults: MaestroDeviceConfiguration.Android,
    ) : PlatformSupportedDevices()

    data class Ios(
        override val deviceCombinations: List<MaestroDeviceConfiguration.Ios>,
        override val defaults: MaestroDeviceConfiguration.Ios,
    ) : PlatformSupportedDevices()

    data class Web(
        override val deviceCombinations: List<MaestroDeviceConfiguration.Web>,
        override val defaults: MaestroDeviceConfiguration.Web,
    ) : PlatformSupportedDevices()
}

// --- Client ---

internal object DeviceCatalogClient {

//    private val baseUrl: String
//        get() = System.getenv("MAESTRO_API_URL") ?: "https://api.copilot.mobile.dev"
//
//    private val client = HttpClient.build(name = "DeviceCatalogClient")
//
//    private val json = jacksonObjectMapper().apply {
//        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
//    }

    fun fetchSupportedDevices(): SupportedDevicesResponse {
//        val request = Request.Builder()
//            .url("$baseUrl/v2/supported-devices")
//            .get()
//            .build()

//        val payload = client.newCall(request).execute().use { response ->
//            json.readValue(response.body?.bytes(), SupportedDevicesResponse::class.java)
//        }
//
//        return SupportedDevicesResponse(
//            ios = requireNotNull(payload.ios) { "API response missing iOS device configuration" },
//            android = requireNotNull(payload.android) { "API response missing Android device configuration" },
//            web = requireNotNull(payload.web) { "API response missing Web device configuration" },
//        )
        return SupportedDevicesResponse(
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
    }
}
