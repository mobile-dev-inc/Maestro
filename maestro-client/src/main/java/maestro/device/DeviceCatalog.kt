package maestro.device

import maestro.device.util.CPU_ARCHITECTURE
import maestro.device.util.EnvUtils
import maestro.device.locale.DeviceLocale

sealed class DeviceSpec {
    abstract val platform: Platform
    abstract val model: String
    abstract val os: String
    abstract val deviceName: String
    abstract val locale: DeviceLocale

    data class Android(
        override val model: String,
        override val os: String,
        override val locale: DeviceLocale,
        val orientation: DeviceOrientation,
        val disableAnimations: Boolean,
        val snapshotKeyHonorModalViews: Boolean,
        val cpuArchitecture: CPU_ARCHITECTURE,
    ) : DeviceSpec() {
        override val platform = Platform.ANDROID
        override val deviceName = "Maestro_ANDROID_${model}_${os}"
        val tag = "google_apis"
        val emulatorImage = "system-images;$os;$tag;${cpuArchitecture.value}"
    }

    data class Ios(
        override val model: String,
        override val os: String,
        override val locale: DeviceLocale,
        val orientation: DeviceOrientation,
        val disableAnimations: Boolean,
    ) : DeviceSpec() {
        override val platform = Platform.IOS
        override val deviceName = "Maestro_IOS_${model}_${os}"
    }

    data class Web(
      override val model: String,
      override val os: String,
      override val locale: DeviceLocale
    ) : DeviceSpec() {
        override val platform = Platform.WEB
        override val deviceName = "Maestro_WEB_${model}_${os}"
    }
}

class CloudCompatibilityException(
    val config: DeviceSpec,
    message: String,
) : Exception(message)

object DeviceCatalog {
    /**
     * Resolves device specifications with platform-aware defaults
     * This returns a valid device spec which is not environment validated
     * Environment specific validation for modal & os can happen on their respective Environment (Local, Cloud, etc.)
     */
    fun resolve(
        platform: String,
        model: String? = null,
        os: String? = null,
        locale: String? = null,
        orientation: DeviceOrientation? = null,
        systemArchitecture: CPU_ARCHITECTURE? = null,
    ): DeviceSpec {
        val platform = Platform.fromString(platform)
            ?: throw IllegalArgumentException("Unsupported platform $platform. Please specify one of: android, ios, web")

        return when (platform) {
            Platform.ANDROID -> {
                DeviceSpec.Android(
                    model = model ?: "pixel_5",
                    os = os ?: "android-34",
                    locale = DeviceLocale.fromString(locale ?: "en_US", platform),
                    orientation = orientation ?: DeviceOrientation.PORTRAIT,
                    disableAnimations = false,
                    snapshotKeyHonorModalViews = false,
                    cpuArchitecture = systemArchitecture ?: EnvUtils.getMacOSArchitecture(),
                )
            }
            Platform.IOS -> {
                DeviceSpec.Ios(
                    model = model ?: "iphone_11",
                    os = os ?: "ios_26",
                    locale = DeviceLocale.fromString(locale ?: "en_US", platform),
                    orientation = orientation ?: DeviceOrientation.PORTRAIT,
                    disableAnimations = false,
                )
            }
            Platform.WEB -> {
                DeviceSpec.Web(
                    model = model ?: "pixel_11",
                    os = os ?: "web_26",
                    locale = DeviceLocale.fromString(locale ?: "en_US", platform),
                )
            }
        }
    }
}
