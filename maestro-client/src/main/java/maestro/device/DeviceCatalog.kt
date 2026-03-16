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

/**
 * Returned Sealed class that has all non-nullable values
 */
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
        val snapshotKeyHonorModalViews: Boolean,
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

/**
 * Request for setting up device config
 */
sealed class DeviceRequest {
    abstract val platform: Platform

    data class Android(
        val model: String? = null,
        val os: String? = null,
        val locale: String? = null,
        val orientation: DeviceOrientation? = null,
        val disableAnimations: Boolean? = null,
        val systemArchitecture: CPU_ARCHITECTURE? = null,
    ) : DeviceRequest() {
        override val platform = Platform.ANDROID
    }

    data class Ios(
        val model: String? = null,
        val os: String? = null,
        val locale: String? = null,
        val orientation: DeviceOrientation? = null,
        val disableAnimations: Boolean? = null,
        val snapshotKeyHonorModalViews: Boolean? = null,
    ) : DeviceRequest() {
        override val platform = Platform.IOS
    }

    data class Web(
        val model: String? = null,
        val os: String? = null,
        val locale: String? = null,
    ) : DeviceRequest() {
        override val platform = Platform.WEB
    }
}

object DeviceCatalog {
    /**
     * Resolves device specifications with platform-aware defaults.
     * This returns a valid device spec which is not environment validated.
     * Environment specific validation for model & os can happen on their respective Environment (Local, Cloud, etc.)
     */
    fun resolve(request: DeviceRequest): DeviceSpec {
        return when (request) {
            is DeviceRequest.Android -> DeviceSpec.Android(
                model = request.model ?: "pixel_6",
                os = request.os ?: "android-33",
                locale = DeviceLocale.fromString(request.locale ?: "en_US", Platform.ANDROID),
                orientation = request.orientation ?: DeviceOrientation.PORTRAIT,
                disableAnimations = request.disableAnimations ?: false,
                cpuArchitecture = request.systemArchitecture ?: CPU_ARCHITECTURE.ARM64,
            )
            is DeviceRequest.Ios -> DeviceSpec.Ios(
                model = request.model ?: "iPhone-11",
                os = request.os ?: "iOS-18-2",
                locale = DeviceLocale.fromString(request.locale ?: "en_US", Platform.IOS),
                orientation = request.orientation ?: DeviceOrientation.PORTRAIT,
                disableAnimations = request.disableAnimations ?: false,
                snapshotKeyHonorModalViews = request.snapshotKeyHonorModalViews ?: false,
            )
            is DeviceRequest.Web -> DeviceSpec.Web(
                model = request.model ?: "chromium",
                os = request.os ?: "default",
                locale = DeviceLocale.fromString(request.locale ?: "en_US", Platform.WEB),
            )
        }
    }
}
