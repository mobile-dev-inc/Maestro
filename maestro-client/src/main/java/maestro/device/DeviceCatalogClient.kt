package maestro.device

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import maestro.utils.HttpClient
import okhttp3.Request

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

internal object DeviceCatalogClient {
    private val baseUrl: String
        get() = System.getenv("MAESTRO_API_URL") ?: "https://api.copilot.mobile.dev"

    private val client = HttpClient.build(name = "DeviceCatalogClient")

    private val json = jacksonObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    fun fetchSupportedDevices(): SupportedDevicesResponse {
        val request = Request.Builder()
            .url("$baseUrl/v2/device/list")
            .get()
            .build()

        val payload = client.newCall(request).execute().use { response ->
            json.readValue(response.body?.bytes(), SupportedDevicesResponse::class.java)
        }

        return SupportedDevicesResponse(
            ios = requireNotNull(payload.ios) { "API response missing iOS device configuration" },
            android = requireNotNull(payload.android) { "API response missing Android device configuration" },
            web = requireNotNull(payload.web) { "API response missing Web device configuration" },
        )
    }
}
