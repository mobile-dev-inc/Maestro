package maestro.device

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import maestro.device.locale.DeviceLocale
import maestro.device.serialization.DeviceLocaleKSerializer

@Serializable
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
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "platform")
@JsonSubTypes(
  JsonSubTypes.Type(DeviceSpec.Android::class, name = "ANDROID"),
  JsonSubTypes.Type(DeviceSpec.Ios::class, name = "IOS"),
  JsonSubTypes.Type(DeviceSpec.Web::class, name = "WEB"),
)
@Serializable
sealed class DeviceSpec {
    abstract val platform: Platform
    abstract val model: String
    abstract val os: String
    abstract val osVersion: Int
    abstract val deviceName: String
    @Serializable(with = DeviceLocaleKSerializer::class)
    abstract val locale: DeviceLocale

    @Serializable
    @SerialName("ANDROID")
    data class Android(
        override val model: String,
        override val os: String,
        @Serializable(with = DeviceLocaleKSerializer::class)
        override val locale: DeviceLocale,
        val orientation: DeviceOrientation,
        val disableAnimations: Boolean,
        val cpuArchitecture: CPU_ARCHITECTURE,
    ) : DeviceSpec() {
        override val platform = Platform.ANDROID
        override val osVersion: Int = os.removePrefix("android-").toIntOrNull() ?: 0
        override val deviceName = "Maestro_ANDROID_${model}_${os}"
        val tag = "google_apis"
        val emulatorImage = "system-images;$os;$tag;${cpuArchitecture.value}"
    }

    @Serializable
    @SerialName("IOS")
    data class Ios(
        override val model: String,
        override val os: String,
        @Serializable(with = DeviceLocaleKSerializer::class)
        override val locale: DeviceLocale,
        val orientation: DeviceOrientation,
        val disableAnimations: Boolean,
        val snapshotKeyHonorModalViews: Boolean,
    ) : DeviceSpec() {
        override val platform = Platform.IOS
        override val osVersion: Int = os.removePrefix("iOS-").substringBefore("-").toIntOrNull() ?: 0
        override val deviceName = "Maestro_IOS_${model}_${osVersion}"
    }

    @Serializable
    @SerialName("WEB")
    data class Web(
      override val model: String,
      override val os: String,
      @Serializable(with = DeviceLocaleKSerializer::class)
      override val locale: DeviceLocale
    ) : DeviceSpec() {
        override val platform = Platform.WEB
        override val osVersion: Int = 0
        override val deviceName = "Maestro_WEB_${model}_${osVersion}"
    }

    companion object {
        /**
         * Creates a fully resolved DeviceSpec from a DeviceRequest, filling in platform-aware defaults.
         * The returned spec is not environment-validated.
         * Environment-specific validation for model & os can happen via SupportedDevices.validate().
         */
        fun fromRequest(request: DeviceSpecRequest): DeviceSpec {
            return when (request) {
                is DeviceSpecRequest.Android -> Android(
                    model = request.model ?: "pixel_6",
                    os = request.os ?: "android-33",
                    locale = DeviceLocale.fromString(request.locale ?: "en_US", Platform.ANDROID),
                    orientation = request.orientation ?: DeviceOrientation.PORTRAIT,
                    disableAnimations = request.disableAnimations ?: true,
                    cpuArchitecture = request.cpuArchitecture ?: CPU_ARCHITECTURE.ARM64,
                )
                is DeviceSpecRequest.Ios -> Ios(
                    model = request.model ?: "iPhone-11",
                    os = request.os ?: "iOS-17-5",
                    locale = DeviceLocale.fromString(request.locale ?: "en_US", Platform.IOS),
                    orientation = request.orientation ?: DeviceOrientation.PORTRAIT,
                    disableAnimations = request.disableAnimations ?: true,
                    snapshotKeyHonorModalViews = request.snapshotKeyHonorModalViews ?: true,
                )
                is DeviceSpecRequest.Web -> Web(
                    model = request.model ?: "chromium",
                    os = request.os ?: "default",
                    locale = DeviceLocale.fromString(request.locale ?: "en_US", Platform.WEB),
                )
            }
        }
    }
}

/**
 * Request for setting up device config
 */
sealed class DeviceSpecRequest {
    abstract val platform: Platform

    data class Android(
        val model: String? = null,
        val os: String? = null,
        val locale: String? = null,
        val orientation: DeviceOrientation? = null,
        val disableAnimations: Boolean? = null,
        val cpuArchitecture: CPU_ARCHITECTURE? = null,
    ) : DeviceSpecRequest() {
        override val platform = Platform.ANDROID
    }

    data class Ios(
        val model: String? = null,
        val os: String? = null,
        val locale: String? = null,
        val orientation: DeviceOrientation? = null,
        val disableAnimations: Boolean? = null,
        val snapshotKeyHonorModalViews: Boolean? = null,
    ) : DeviceSpecRequest() {
        override val platform = Platform.IOS
    }

    data class Web(
        val model: String? = null,
        val os: String? = null,
        val locale: String? = null,
    ) : DeviceSpecRequest() {
        override val platform = Platform.WEB
    }
}
