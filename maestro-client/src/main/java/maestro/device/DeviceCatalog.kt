package maestro.device
import maestro.DeviceOrientation
import maestro.Platform
import maestro.locale.DeviceLocale

data class DeviceSpec(
    val locale: DeviceLocale,
    val deviceOS: DeviceOS,
    val deviceModel: DeviceModel,
    val deviceOrientation: DeviceOrientation
) {
    /**
     * Generates the device name based on the model and OS version.
     * This is used for creating simulators/emulators.
     * 
     * Format:
     * - Android: "Maestro_<Model>_API_<Version>" (e.g., "Maestro_Pixel_6_API_30")
     * - iOS: "Maestro_<Model>_<Version>" (e.g., "Maestro_iPhone11_16")
     * - Web: "Web_Browser"
     * 
     * @param shardIndex Optional shard index to append to the device name (for parallel execution)
     * @return Device name suitable for simulator/emulator creation
     */
    fun generateDeviceName(shardIndex: Int? = null): String {
        val baseName = when (deviceModel.platform) {
            Platform.ANDROID -> {
                val modelName = deviceModel.displayName.replace(" ", "_")
                "Maestro_${modelName}_API_${deviceOS.version}"
            }
            Platform.IOS -> {
                val modelName = deviceModel.displayName.replace(" ", "")
                "Maestro_${modelName}_${deviceOS.version}"
            }
            Platform.WEB -> "Web_Browser"
        }
        
        return if (shardIndex != null) {
            "${baseName}_${shardIndex + 1}"
        } else {
            baseName
        }
    }
}

object DeviceCatalog {
    /**
     * Get device specifications with optional parameters.
     * If parameters are not provided, defaults will be used based on the platform.
     * 
     * @param platform The target platform (Android, iOS, Web)
     * @param locale Device locale string (e.g., "en_US"). Defaults to "en_US"
     * @param osVersion OS version number. Defaults to platform-specific default version
     * @param deviceModelId Device model identifier string. Defaults to platform-specific default model
     * @param deviceOrientation Device orientation. Defaults to PORTRAIT
     * @return Complete DeviceSpec with all parameters
     * @throws LocaleValidationException if the locale string is invalid
     * @throws IllegalArgumentException if the OS version is not supported for the platform
     */
    fun getDeviceSpecs(
        platform: Platform,
        locale: String? = null,
        osVersion: Int? = null,
        deviceModelId: String? = null,
        deviceOrientation: DeviceOrientation? = null
    ): DeviceSpec {
        return DeviceSpec(
            locale = DeviceLocale.fromString(locale, platform),
            deviceOS = DeviceOS.fromVersion(osVersion, platform),
            deviceModel = DeviceModel.fromModelId(deviceModelId, platform),
            deviceOrientation = deviceOrientation ?: DeviceOrientation.PORTRAIT
        )
    }
    
    /**
     * Get all supported OS versions for a platform
     */
    fun getSupportedOSVersions(platform: Platform): List<Int> {
        return DeviceOS.getSupportedVersions(platform)
    }

    /**
     * Get default OS Version
     */
    fun getDefaultOSVersion(platform: Platform): DeviceOS {
        return DeviceOS.getDefault(platform)
    }

    /**
     * Get all supported locales for a platform
     */
    fun getAllLocales(platform: Platform): List<DeviceLocale> {
        return DeviceLocale.all(platform)
    }

    /**
     * Get all supported models for a platform
     */
    fun getAllModels(platform: Platform): List<DeviceModel> {
        return DeviceModel.getAllCommon(platform)
    }


    /**
     * Gets all possible device specification combinations for a platform.
     * This generates the Cartesian product of:
     * - All supported locales
     * - All supported OS versions
     * - All common device models
     * - All device orientations
     * 
     * Warning: This can generate a large number of combinations (potentially thousands).
     * For example, Android might have: 50 locales × 6 OS versions × 5 models × 4 orientations = 6,000 specs
     * 
     * @param platform The target platform
     * @return List of all possible DeviceSpec combinations
     */
    fun getAllPossibleCombinations(platform: Platform): List<DeviceSpec> {
        val allLocales = DeviceLocale.all(platform)
        val allOSVersions = DeviceOS.getAllSupported(platform)
        val allModels = DeviceModel.getAllCommon(platform)
        val allOrientations = DeviceOrientation.values().toList()
        
        return buildList {
            for (locale in allLocales) {
                for (os in allOSVersions) {
                    for (model in allModels) {
                        for (orientation in allOrientations) {
                            add(
                                DeviceSpec(
                                    locale = locale,
                                    deviceOS = os,
                                    deviceModel = model,
                                    deviceOrientation = orientation
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
