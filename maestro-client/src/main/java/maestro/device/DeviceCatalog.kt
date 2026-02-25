package maestro.device

import maestro.DeviceOrientation
import maestro.locale.DeviceLocale

sealed class MaestroDeviceConfiguration {
    abstract val platform: Platform
    abstract fun generateDeviceName(shardIndex: Int? = null): String

    data class Android(
        val deviceModel: String,
        val emulatorImage: String,
        val locale: DeviceLocale,
        val orientation: DeviceOrientation,
        val disableAnimations: Boolean,
        val snapshotKeyHonorModalViews: Boolean,
    ) : MaestroDeviceConfiguration() {
        override val platform = Platform.ANDROID
        override fun generateDeviceName(shardIndex: Int?): String {
            val baseName = "Maestro_ANDROID_${deviceModel}_${emulatorImage}"
            return if (shardIndex != null) "${baseName}_${shardIndex + 1}" else baseName
        }
    }

    data class Ios(
        val deviceModel: String,
        val deviceOs: String,
        val locale: DeviceLocale,
        val orientation: DeviceOrientation,
        val disableAnimations: Boolean,
    ) : MaestroDeviceConfiguration() {
        override val platform = Platform.IOS
        override fun generateDeviceName(shardIndex: Int?): String {
            val baseName = "Maestro_IOS_${deviceModel}_${deviceOs}"
            return if (shardIndex != null) "${baseName}_${shardIndex + 1}" else baseName
        }
    }

    data class Web(
        val browser: String,
    ) : MaestroDeviceConfiguration() {
        override val platform = Platform.WEB
        override fun generateDeviceName(shardIndex: Int?): String {
            val baseName = "Maestro_WEB_${browser}"
            return if (shardIndex != null) "${baseName}_${shardIndex + 1}" else baseName
        }
    }
}

class CloudCompatibilityException(
    val config: MaestroDeviceConfiguration,
    message: String,
) : Exception(message)

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
        return when (platform) {
            Platform.ANDROID -> {
                val defaults = cloudDevice().android.defaults
                val config = MaestroDeviceConfiguration.Android(
                    deviceModel = model ?: defaults.deviceModel,
                    emulatorImage = os ?: defaults.deviceOs,
                    locale = DeviceLocale.fromString(locale ?: defaults.locale, platform),
                    orientation = orientation ?: DEFAULT_ORIENTATION,
                    disableAnimations = defaults.disableAnimations,
                    snapshotKeyHonorModalViews = defaults.snapshotKeyHonorModalViews,
                )
                checkCloudCompatibility(config)
                config
            }
            Platform.IOS -> {
                val defaults = cloudDevice().ios.defaults
                val config = MaestroDeviceConfiguration.Ios(
                    deviceModel = model ?: defaults.deviceModel,
                    deviceOs = os ?: defaults.deviceOs,
                    locale = DeviceLocale.fromString(locale ?: defaults.locale, platform),
                    orientation = orientation ?: DEFAULT_ORIENTATION,
                    disableAnimations = defaults.disableAnimations,
                )
                checkCloudCompatibility(config)
                config
            }
            Platform.WEB -> {
                val defaults = cloudDevice().web.defaults
                val config = MaestroDeviceConfiguration.Web(
                    browser = model ?: defaults.deviceModel,
                )
                checkCloudCompatibility(config)
                config
            }
        }
    }

    private fun checkCloudCompatibility(config: MaestroDeviceConfiguration) {
        val combinations = platformCloudDeviceData(config.platform).deviceCombinations
        val (model, os) = when (config) {
            is MaestroDeviceConfiguration.Android -> config.deviceModel to config.emulatorImage
            is MaestroDeviceConfiguration.Ios     -> config.deviceModel to config.deviceOs
            is MaestroDeviceConfiguration.Web     ->
                config.browser to (combinations.firstOrNull { it.deviceModel == config.browser }?.deviceOs ?: "")
        }

        val modelsForPlatform = combinations.map { it.deviceModel }.distinct()
        if (model !in modelsForPlatform) {
            throw CloudCompatibilityException(
                config,
                "Model '$model' is not available in the cloud. Available models: $modelsForPlatform"
            )
        }

        val matchExists = combinations.any { it.deviceModel == model && it.deviceOs == os }
        if (!matchExists) {
            val supportedVersions = combinations.filter { it.deviceModel == model }.map { it.deviceOs }
            throw CloudCompatibilityException(
                config,
                "OS version $os is not supported for '$model' in the cloud. Supported versions: $supportedVersions"
            )
        }
    }

    fun defaultOs(platform: Platform): String = platformCloudDeviceData(platform).defaults.deviceOs

    fun allCloudAvailableOs(platform: Platform): List<String> =
        platformCloudDeviceData(platform).deviceCombinations.map { it.deviceOs }.distinct()
}
