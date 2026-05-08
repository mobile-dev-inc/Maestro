package maestro.device

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
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
 * Strongly typed device configuration. Callers must provide `model` and `os`;
 * all other fields have sensible defaults that can be overridden when needed.
 *
 * Derived values (osVersion, deviceName, emulatorImage, tag) are computed at
 * access time via `get()` properties — they are not stored in the data class
 * and therefore never serialized or persisted.
 *
 * Serialization is sparse: fields that match their constructor default are
 * omitted from the JSON output. See DeviceSpecSparseSerializer.
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
    ) : DeviceSpec() {
        init {
            require(model.isNotBlank()) { "DeviceSpec.Android: model cannot be blank" }
            require(os.isNotBlank()) { "DeviceSpec.Android: os cannot be blank" }
        }

        override val platform = Platform.ANDROID
        override val osVersion: Int get() = os.removePrefix("android-").toIntOrNull() ?: 0
        override val deviceName: String get() = "Maestro_ANDROID_${model}_${os}"
        val tag: String get() = "google_apis"
        val emulatorImage: String get() = "system-images;$os;$tag;${cpuArchitecture.value}"

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
