package maestro.device

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonValue
import maestro.device.locale.AndroidLocale
import maestro.device.locale.DeviceLocale
import maestro.device.locale.IosLocale
import maestro.device.locale.WebLocale

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
 * The semantic system-image family: GOOGLE_APIS (rootable/userdebug) vs
 * GOOGLE_APIS_PLAYSTORE (production Play Store). A page-size variant like
 * `google_apis_ps16k` is the same family, resolved per host by DeviceService.
 */
enum class SystemImageTag(@JsonValue val value: String) {
    GOOGLE_APIS("google_apis"),
    GOOGLE_APIS_PLAYSTORE("google_apis_playstore");

    companion object {
        fun fromString(value: String): SystemImageTag =
            entries.firstOrNull { it.value == value }
                ?: throw IllegalArgumentException(
                    "Unknown system-image tag: '$value'. Must be one of: ${entries.joinToString { it.value }}"
                )

        // `google_apis_ps16k` -> GOOGLE_APIS, `google_apis_playstore_ps16k` -> GOOGLE_APIS_PLAYSTORE
        fun fromImageTag(imageTag: String): SystemImageTag =
            fromString(imageTag.removeSuffix("_ps16k"))
    }
}

/**
 * Strongly typed device configuration. Callers must provide `model` and `os`;
 * all other fields have sensible defaults that can be overridden when needed.
 *
 * The spec carries intent (os, tag, abi), not a concrete system-image package;
 * each host resolves the package against its own SDK (DeviceService.resolveSystemImage).
 *
 * Derived values (osVersion, deviceName) are computed get() properties, never
 * serialized. Serialization is sparse (see DeviceSpecSparseSerializer).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "platform")
@JsonSubTypes(
  JsonSubTypes.Type(DeviceSpec.Android::class, name = "ANDROID"),
  JsonSubTypes.Type(DeviceSpec.Ios::class, name = "IOS"),
  JsonSubTypes.Type(DeviceSpec.Web::class, name = "WEB"),
)
sealed class DeviceSpec {
    abstract val platform: Platform
    abstract val model: String
    abstract val os: String
    abstract val locale: DeviceLocale
    abstract val osVersion: Int
    abstract val deviceName: String

    data class Android(
        override val model: String,
        override val os: String,
        override val locale: AndroidLocale = AndroidLocale.fromString("en_US"),
        val cpuArchitecture: CPU_ARCHITECTURE = CPU_ARCHITECTURE.ARM64,
        val tag: SystemImageTag = SystemImageTag.GOOGLE_APIS,
    ) : DeviceSpec() {
        init {
            require(model.isNotBlank()) { "DeviceSpec.Android: model cannot be blank" }
            require(os.isNotBlank()) { "DeviceSpec.Android: os cannot be blank" }
        }

        override val platform = Platform.ANDROID
        // "android-37.1" -> 37
        override val osVersion: Int get() =
            os.removePrefix("android-").substringBefore(".").toIntOrNull() ?: 0
        // A non-default tag is suffixed so a playstore AVD never reuses a google_apis one.
        override val deviceName: String get() =
            "Maestro_ANDROID_${model}_${os}" +
                if (tag == SystemImageTag.GOOGLE_APIS) "" else "_${tag.value}"

        companion object {
            val DEFAULT: Android = Android(model = "pixel_6", os = "android-33")
        }
    }

    data class Ios(
        override val model: String,
        override val os: String,
        override val locale: IosLocale = IosLocale.EN_US,
    ) : DeviceSpec() {
        init {
            require(model.isNotBlank()) { "DeviceSpec.Ios: model cannot be blank" }
            require(os.isNotBlank()) { "DeviceSpec.Ios: os cannot be blank" }
        }

        override val platform = Platform.IOS
        override val osVersion: Int get() = os.removePrefix("iOS-").substringBefore("-").toIntOrNull() ?: 0
        override val deviceName: String get() = "Maestro_IOS_${model}_${osVersion}"

        companion object {
            val DEFAULT: Ios = Ios(model = "iPhone-11", os = "iOS-17-5")
        }
    }

    data class Web(
      override val model: String,
      override val os: String,
      override val locale: WebLocale = WebLocale.EN_US,
    ) : DeviceSpec() {
        init {
            require(model.isNotBlank()) { "DeviceSpec.Web: model cannot be blank" }
            require(os.isNotBlank()) { "DeviceSpec.Web: os cannot be blank" }
        }

        override val platform = Platform.WEB
        override val osVersion: Int get() = 0
        override val deviceName: String get() = "Maestro_WEB_${model}_${osVersion}"

        companion object {
            val DEFAULT: Web = Web(model = "chromium", os = "default")
        }
    }
}
