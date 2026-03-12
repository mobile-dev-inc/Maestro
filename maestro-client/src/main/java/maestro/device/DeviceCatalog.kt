package maestro.device

import maestro.device.locale.DeviceLocale

enum class CPU_ARCHITECTURE(val value: String) {
  X86_64("x86_64"),
  ARM64("arm64-v8a"),
  UNKNOWN("unknown");

  companion object {
    fun fromString(p: String?): Platform? {
      return Platform.entries.firstOrNull { it.description.equals(p, ignoreCase = true) }
    }
  }
}

sealed class DeviceSpec {
    abstract val platform: Platform
    abstract val model: String
    abstract val os: String
    abstract val osVersion: Int
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
        override val osVersion: Int = os.removePrefix("android-").toIntOrNull() ?: 0
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
        override val osVersion: Int = os.removePrefix("iOS-").substringBefore("-").toIntOrNull() ?: 0
    }

    data class Web(
      override val model: String,
      override val os: String,
      override val locale: DeviceLocale
    ) : DeviceSpec() {
        override val platform = Platform.WEB
        override val deviceName = "Maestro_WEB_${model}_${os}"
        override val osVersion: Int = 0
    }
}

object DeviceCatalog {
    /**
     * Resolves device specifications with platform-aware defaults
     * This returns a valid device spec which is not environment validated
     * Environment specific validation for modal & os can happen on their respective Environment (Local, Cloud, etc.)
     */
    fun resolve(
        platform: String,
        model: String?,
        os: String?,
        locale: String?,
        orientation: DeviceOrientation?,
        disableAnimations: Boolean?,
        snapshotKeyHonorModalViews: Boolean?,
        systemArchitecture: CPU_ARCHITECTURE?,
    ): DeviceSpec {
        val resolvedLocale = locale ?: "en_US"
        val resolvedOrientation = orientation ?: DeviceOrientation.PORTRAIT
        val resolvedDisableAnimation = disableAnimations ?: false
        val resolvedSnapshotKeyHonorModalViews = snapshotKeyHonorModalViews ?: false
        val resolvedSystemArchitecture = systemArchitecture ?: CPU_ARCHITECTURE.ARM64

        val platform = Platform.fromString(platform)
            ?: throw IllegalArgumentException("Unsupported platform $platform. Please specify one of: android, ios, web")

        return when (platform) {
            Platform.ANDROID -> {
                DeviceSpec.Android(
                    model = model ?: "pixel_6",
                    os = os ?: "android-34",
                    locale = DeviceLocale.fromString(resolvedLocale, platform),
                    orientation = resolvedOrientation,
                    disableAnimations = resolvedDisableAnimation,
                    snapshotKeyHonorModalViews = resolvedSnapshotKeyHonorModalViews,
                    cpuArchitecture = resolvedSystemArchitecture,
                )
            }
            Platform.IOS -> {
                DeviceSpec.Ios(
                    model = model ?: "iPhone-11",
                    os = os ?: "iOS-18-2",
                    locale = DeviceLocale.fromString(resolvedLocale, platform),
                    orientation = resolvedOrientation,
                    disableAnimations = resolvedDisableAnimation,
                )
            }
            Platform.WEB -> {
                DeviceSpec.Web(
                    model = model ?: "chromium",
                    os = os ?: "default",
                    locale = DeviceLocale.fromString(resolvedLocale, platform),
                )
            }
        }
    }
}
