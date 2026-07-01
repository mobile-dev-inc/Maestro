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

        // emulatorImage is an sdkmanager "SDK-style path": system-images;<platform>;<dir>;<abi>.
        // The 3rd segment is the on-disk directory under system-images/<platform>/, NOT a "tag"
        // (the image's SystemImage.TagId). They coincided for single-tag images (≤36), but API 37's
        // multi-tag 16 KB image has dir "google_apis_ps16k" while its tag id is plain "google_apis",
        // so the dir name is rejected as an avdmanager --tag.
        //
        // CRITICAL: a fully-qualified --package makes avdmanager derive the tag and ABI from it, so
        // createAndroidDevice passes ONLY --package — never --tag/--abi. This path is the sole source
        // of truth; keep it that way.
        //
        // API 37+: platform carries a minor version ("android-37.0", not "android-37"; Google's
        // permanent minor-SDK scheme since Android 16 QPR2) and only the 16 KB ("…_ps16k") dir ships.
        // https://android-developers.googleblog.com/2025/12/android-16-qpr2-is-released.html
        // https://developer.android.com/guide/practices/page-sizes
        private val imagePlatform: String get() = if (osVersion >= 37) "$os.0" else os
        private val imageDir: String get() = if (osVersion >= 37) "google_apis_ps16k" else "google_apis"
        val emulatorImage: String get() = "system-images;$imagePlatform;$imageDir;${cpuArchitecture.value}"

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
