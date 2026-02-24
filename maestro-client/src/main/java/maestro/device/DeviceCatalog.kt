package maestro.device

import maestro.DeviceOrientation
import maestro.locale.DeviceLocale

data class MaestroDeviceConfiguration(
    val platform: Platform,
    val model: String,
    val os: String,
    val locale: DeviceLocale,
    val orientation: DeviceOrientation,
) {
    fun generateDeviceName(shardIndex: Int? = null): String {
        val baseName = "Maestro_${platform}_${model}_${os}"
        return if (shardIndex != null) "${baseName}_${shardIndex + 1}" else baseName
    }
}

class CloudCompatibilityException(val config: MaestroDeviceConfiguration, message: String) : Exception(message)

object DeviceCatalog {
    private val DEFAULT_ORIENTATION = DeviceOrientation.PORTRAIT
    private var cachedResponse: SupportedDevicesResponse? = null

    /** For tests only — injects a response without hitting the network. */
    internal fun initForTest(response: SupportedDevicesResponse) {
        cachedResponse = response
    }

    private fun cloudDevice(): SupportedDevicesResponse =
        cachedResponse ?: DeviceCatalogClient.fetchSupportedDevices().also { cachedResponse = it }

    private fun platformCloudDeviceData(platform: Platform): PlatformSupportedDevices = when (platform) {
        Platform.IOS     -> cloudDevice().ios
        Platform.ANDROID -> cloudDevice().android
        Platform.WEB     -> cloudDevice().web
    }

    /**
     * Resolves device specifications with platform-aware defaults.
     * Throws [CloudCompatibilityException] if the resolved config is not cloud-compatible.
     */
    fun resolve(
      platform: Platform,
      model: String? = null,
      os: String? = null,
      locale: String? = null,
      orientation: DeviceOrientation? = null,
    ): MaestroDeviceConfiguration {
        val data = platformCloudDeviceData(platform)
        val config = MaestroDeviceConfiguration(
            platform = platform,
            model = model ?: data.defaults.deviceModel,
            os = os ?: data.defaults.deviceOs,
            locale = DeviceLocale.fromString(locale ?: data.defaults.locale, platform),
            orientation = orientation ?: DEFAULT_ORIENTATION,
        )

        checkCloudCompatibility(config)
        return config
    }

    fun defaultOs(platform: Platform): String = platformCloudDeviceData(platform).defaults.deviceOs

    fun allCloudAvailableOs(platform: Platform): List<String> =
        platformCloudDeviceData(platform).deviceCombinations.map { it.deviceOs }.distinct()


    private fun checkCloudCompatibility(config: MaestroDeviceConfiguration) {
        val combinations = platformCloudDeviceData(config.platform).deviceCombinations
        val modelsForPlatform = combinations.map { it.deviceModel }.distinct()
        if (config.model !in modelsForPlatform) {
            throw CloudCompatibilityException(
                config,
                "Model '${config.model}' is not available in the cloud. Available models: $modelsForPlatform"
            )
        }

        val matchExists = combinations.any { it.deviceModel == config.model && it.deviceOs == config.os }
        if (!matchExists) {
            val supportedVersions = combinations.filter { it.deviceModel == config.model }.map { it.deviceOs }
            throw CloudCompatibilityException(
                config,
                "OS version ${config.os} is not supported for '${config.model}' in the cloud. Supported versions: $supportedVersions"
            )
        }
    }
}
