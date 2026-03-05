package maestro.device

import maestro.DeviceOrientation
import maestro.device.util.CPU_ARCHITECTURE
import maestro.device.util.EnvUtils
import maestro.locale.DeviceLocale

sealed class MaestroDeviceConfiguration {
    abstract val platform: Platform
    abstract val deviceModel: String
    abstract val deviceOs: String
    abstract val deviceName: String
    abstract val locale: DeviceLocale

    data class Android(
        override val deviceModel: String,
        override val deviceOs: String,
        override val locale: DeviceLocale,
        val orientation: DeviceOrientation,
        val disableAnimations: Boolean,
        val snapshotKeyHonorModalViews: Boolean,
        val cpuArchitecture: CPU_ARCHITECTURE,
    ) : MaestroDeviceConfiguration() {
        override val platform = Platform.ANDROID
        override val deviceName = "Maestro_ANDROID_${deviceModel}_${deviceOs}"
        val tag = "google_apis"
        val emulatorImage = "system-images;$deviceOs;$tag;${cpuArchitecture.value}"
    }

    data class Ios(
        override val deviceModel: String,
        override val deviceOs: String,
        override val locale: DeviceLocale,
        val orientation: DeviceOrientation,
        val disableAnimations: Boolean,
    ) : MaestroDeviceConfiguration() {
        override val platform = Platform.IOS
        override val deviceName = "Maestro_IOS_${deviceModel}_${deviceOs}"
    }

    data class Web(
      override val deviceModel: String,
      override val deviceOs: String,
      override val locale: DeviceLocale
    ) : MaestroDeviceConfiguration() {
        override val platform = Platform.WEB
        override val deviceName = "Maestro_WEB_${deviceModel}_${deviceOs}"
    }
}

class CloudCompatibilityException(
    val config: MaestroDeviceConfiguration,
    message: String,
) : Exception(message)

object DeviceCatalog {
    private var cachedResponse: SupportedDevicesResponse? = null

    /** For tests only — injects a response without hitting the network. */
    internal fun initForTest(response: SupportedDevicesResponse) {
        cachedResponse = response
    }

    private fun cloudDevice(): SupportedDevicesResponse =
        cachedResponse ?: DeviceCatalogClient.fetchSupportedDevices().also { cachedResponse = it }

    /**
     * Resolves device specifications with platform-aware defaults.
     * Throws [CloudCompatibilityException] if the resolved config is not cloud-compatible.
     */
    fun resolve(
        platform: String,
        model: String? = null,
        os: String? = null,
        locale: String? = null,
        orientation: DeviceOrientation? = null,
        systemArchitecture: CPU_ARCHITECTURE? = null,
    ): MaestroDeviceConfiguration {
        val platform = Platform.fromString(platform)
            ?: throw IllegalArgumentException("Unsupported platform $platform. Please specify one of: android, ios, web")

        return when (platform) {
            Platform.ANDROID -> {
                val defaults: MaestroDeviceConfiguration.Android = cloudDevice().android.defaults
                defaults.copy(
                    deviceModel = model ?: defaults.deviceModel,
                    deviceOs = os ?: defaults.deviceOs,
                    locale = locale?.let { DeviceLocale.fromString(it, platform) } ?: defaults.locale,
                    orientation = orientation ?: defaults.orientation,
                    cpuArchitecture = systemArchitecture ?: EnvUtils.getMacOSArchitecture(),
                )
            }
            Platform.IOS -> {
                val defaults: MaestroDeviceConfiguration.Ios = cloudDevice().ios.defaults
                defaults.copy(
                    deviceModel = model ?: defaults.deviceModel,
                    deviceOs = os ?: defaults.deviceOs,
                    locale = locale?.let { DeviceLocale.fromString(it, platform) } ?: defaults.locale,
                    orientation = orientation ?: defaults.orientation,
                )
            }
            Platform.WEB -> {
                val defaults: MaestroDeviceConfiguration.Web = cloudDevice().web.defaults
                defaults.copy(
                    deviceModel = model ?: defaults.deviceModel,
                    deviceOs = os ?: defaults.deviceOs,
                )
            }
        }
    }

  fun checkLocalCompatibility(config: MaestroDeviceConfiguration) {

  }

     fun checkCloudCompatibility(config: MaestroDeviceConfiguration) {
        val combinations = cloudDevice().forPlatform(config.platform).deviceCombinations

        val modelsForPlatform = combinations.map { it.deviceModel }.distinct()
        if (config.deviceModel !in modelsForPlatform) {
            throw CloudCompatibilityException(
                config,
                "Model '${config.deviceModel}' is not available in the cloud. Available models: $modelsForPlatform"
            )
        }

        val matchExists = combinations.any { it.deviceModel == config.deviceModel && it.deviceOs == config.deviceOs }
        if (!matchExists) {
            val supportedVersions = combinations.filter { it.deviceModel == config.deviceModel }.map { it.deviceOs }
            throw CloudCompatibilityException(
                config,
                "OS version ${config.deviceOs} is not supported for '${config.deviceModel}' in the cloud. Supported versions: $supportedVersions"
            )
        }
    }
}
