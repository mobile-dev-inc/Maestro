package maestro.device

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
//import com.fasterxml.jackson.databind.DeserializationFeature
//import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
//import maestro.utils.HttpClient
//import okhttp3.Request

// --- Response types ---

data class SupportedDevicesResponse(
    val ios: IosSupportedDevices,
    val android: AndroidSupportedDevices,
    val web: WebSupportedDevices,
)

/** Common interface — lets DeviceCatalog access deviceCombinations and defaults without platform-casting. */
interface PlatformSupportedDevices {
    val deviceCombinations: List<DeviceCombination>
    val defaults: PlatformDefaults
}

/** Common defaults fields shared by all platforms. */
interface PlatformDefaults {
    val deviceModel: String
    val deviceOs: String
    val locale: String
}

// iOS
data class IosSupportedDevices(
    override val deviceCombinations: List<DeviceCombination>,
    override val defaults: IosDefaults,
) : PlatformSupportedDevices

data class IosDefaults(
    override val deviceModel: String,
    override val deviceOs: String,
    override val locale: String,
    val disableAnimations: Boolean = true,
) : PlatformDefaults

// Android
data class AndroidSupportedDevices(
    override val deviceCombinations: List<DeviceCombination>,
    override val defaults: AndroidDefaults,
) : PlatformSupportedDevices

data class AndroidDefaults(
    override val deviceModel: String,
    override val deviceOs: String,
    override val locale: String,
    val disableAnimations: Boolean = true,
    val snapshotKeyHonorModalViews: Boolean = false,
) : PlatformDefaults

// Web
data class WebSupportedDevices(
    override val deviceCombinations: List<DeviceCombination>,
    override val defaults: WebDefaults,
) : PlatformSupportedDevices

data class WebDefaults(
    override val deviceModel: String,
    override val deviceOs: String,
    override val locale: String,
) : PlatformDefaults

data class DeviceCombination(
    val deviceModel: String,
    val deviceOs: String,
)

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
//            json.readValue(response.body?.bytes(), ApiDevicesPayload::class.java)
//        }
//
//        return SupportedDevicesResponse(
//            ios = requireNotNull(payload.ios) { "API response missing iOS device configuration" },
//            android = requireNotNull(payload.android) { "API response missing Android device configuration" },
//            web = requireNotNull(payload.web) { "API response missing Web device configuration" },
//        )

        return SupportedDevicesResponse(
            ios = IosSupportedDevices(
                deviceCombinations = listOf(
                    DeviceCombination("iPhone-16-Pro", "iOS-18-2"),
                    DeviceCombination("iPhone-16",     "iOS-18-2"),
                    DeviceCombination("iPhone-11",     "iOS-17-5"),
                    DeviceCombination("iPhone-11",     "iOS-16-4"),
                ),
                defaults = IosDefaults(
                    deviceModel = "iPhone-16",
                    deviceOs = "iOS-18-2",
                    locale = "en_US",
                    disableAnimations = true,
                ),
            ),
            android = AndroidSupportedDevices(
                deviceCombinations = listOf(
                    DeviceCombination("pixel_6", "system-images;android-34;google_apis;arm64-v8a"),
                    DeviceCombination("pixel_6", "system-images;android-34;google_apis_playstore;arm64-v8a"),
                    DeviceCombination("pixel_6", "system-images;android-33;google_apis;arm64-v8a"),
                    DeviceCombination("pixel_6", "system-images;android-31;google_apis;arm64-v8a"),
                    DeviceCombination("pixel_6", "system-images;android-30;google_apis;arm64-v8a"),
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
                deviceCombinations = listOf(
                    DeviceCombination("chromium", "default"),
                ),
                defaults = WebDefaults(
                    deviceModel = "chromium",
                    deviceOs = "default",
                    locale = "en_US",
                ),
            ),
        )
    }

//    @JsonIgnoreProperties(ignoreUnknown = true)
//    private data class ApiDevicesPayload(
//        val ios: IosSupportedDevices? = null,
//        val android: AndroidSupportedDevices? = null,
//        val web: WebSupportedDevices? = null,
//    )
}
