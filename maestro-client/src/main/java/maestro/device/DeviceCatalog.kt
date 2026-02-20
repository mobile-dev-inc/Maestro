package maestro.device

import maestro.DeviceOrientation
import maestro.locale.DeviceLocale

data class MaestroDeviceConfiguration(
  val platform: Platform,
  val model: String,
  val os: String,
  val locale: DeviceLocale,
  val orientation: DeviceOrientation,
) {
  // Generates the device name based on the model and OS version and sharding
  fun generateDeviceName(shardIndex: Int? = null): String {
    val baseName = "Maestro_${platform}_${model}_${os}"
    return if (shardIndex != null) "${baseName}_${shardIndex + 1}" else baseName
  }
}


class CloudCompatibilityException(val config: MaestroDeviceConfiguration, message: String) : Exception(message)

object DeviceCatalog {
  private const val DEFAULT_LOCALE = "en_US"
  private val DEFAULT_ORIENTATION = DeviceOrientation.PORTRAIT
  private const val DEFAULT_IOS_MODEL = "iPhone-11"
  private const val DEFAULT_IOS_OS = "iOS-17-5"
  private const val DEFAULT_ANDROID_MODEL = "pixel_6"
  private const val DEFAULT_ANDROID_OS = "30"
  private const val DEFAULT_WEB_MODEL = "chromium"
  private const val DEFAULT_WEB_OS = "latest"

  private val CLOUD_CATALOG: Set<MaestroDeviceConfiguration> by lazy { setOf(
    // Android - Pixel 6
    cloudDevice(Platform.ANDROID, "pixel_6", "28"),
    cloudDevice(Platform.ANDROID, "pixel_6", "29"),
    cloudDevice(Platform.ANDROID, "pixel_6", "30"),
    cloudDevice(Platform.ANDROID, "pixel_6", "31"),
    cloudDevice(Platform.ANDROID, "pixel_6", "33"),
    cloudDevice(Platform.ANDROID, "pixel_6", "34"),

    // Android - Pixel XL
    cloudDevice(Platform.ANDROID, "pixel_xl", "30"),
    cloudDevice(Platform.ANDROID, "pixel_xl", "31"),
    cloudDevice(Platform.ANDROID, "pixel_xl", "33"),
    cloudDevice(Platform.ANDROID, "pixel_xl", "34"),

    // iOS - iPhone 11
    cloudDevice(Platform.IOS, "iPhone-11", "iOS-16-2"),
    cloudDevice(Platform.IOS, "iPhone-11", "iOS-17-5"),
    cloudDevice(Platform.IOS, "iPhone-11", "iOS-18-2"),

    // iOS - iPhone 13 mini
    cloudDevice(Platform.IOS, "iPhone-13-mini", "iOS-18-2"),

    // iOS - iPhone 16
    cloudDevice(Platform.IOS, "iPhone-16", "iOS-18-2"),

    // iOS - iPhone 16 Pro
    cloudDevice(Platform.IOS, "iPhone-16-Pro", "iOS-18-2"),

    // iOS - iPhone 16 Pro Max
    cloudDevice(Platform.IOS, "iPhone-16-Pro-Max", "iOS-18-2"),

    // iOS - iPad 10th generation
    cloudDevice(Platform.IOS, "iPad-10th-generation", "iOS-16-2"),
    cloudDevice(Platform.IOS, "iPad-10th-generation", "iOS-17-0"),
    cloudDevice(Platform.IOS, "iPad-10th-generation", "iOS-18-2"),

    // Web device
    cloudDevice(Platform.WEB, "chromium", "latest")
  )}


  /**
   * Resolves device specifications with platform-aware defaults.
   * Throws [CloudCompatibilityException] if the resolved config is not cloud-compatible
   */
  fun resolve(
    platform: Platform,
    model: String? = null,
    os: String? = null,
    locale: String? = null,
    orientation: DeviceOrientation? = null,
  ): MaestroDeviceConfiguration {
    val config = MaestroDeviceConfiguration(
      platform = platform,
      model = model ?: defaultModel(platform),
      os = os ?: defaultOs(platform),
      locale = DeviceLocale.fromString(locale ?: DEFAULT_LOCALE, platform),
      orientation = orientation ?: DEFAULT_ORIENTATION,
    )

    checkCloudCompatibility(config)
    return config
  }

  private fun checkCloudCompatibility(config: MaestroDeviceConfiguration) {
    val platformEntries = CLOUD_CATALOG.filter { it.platform == config.platform }
    if (platformEntries.isEmpty()) return

    val modelsForPlatform = platformEntries.map { it.model }.distinct()
    if (config.model !in modelsForPlatform) {
      throw CloudCompatibilityException(
        config,
        "Model '${config.model}' is not available in the cloud. Available models: $modelsForPlatform"
      )
    }

    // Compare only platform+model+os (ignore locale/orientation for cloud matching)
    val matchExists = platformEntries.any { it.model == config.model && it.os == config.os }
    if (!matchExists) {
      val supportedVersions = platformEntries.filter { it.model == config.model }.map { it.os }
      throw CloudCompatibilityException(
        config,
        "OS version ${config.os} is not supported for '${config.model}' in the cloud. Supported versions: $supportedVersions"
      )
    }
  }

  private fun cloudDevice(platform: Platform, model: String, os: String) = MaestroDeviceConfiguration(
    platform = platform,
    model = model,
    os = os,
    locale = DeviceLocale.fromString(DEFAULT_LOCALE, platform),
    orientation = DEFAULT_ORIENTATION,
  )

  fun defaultModel(platform: Platform): String = when (platform) {
    Platform.IOS -> DEFAULT_IOS_MODEL
    Platform.ANDROID -> DEFAULT_ANDROID_MODEL
    Platform.WEB -> DEFAULT_WEB_MODEL
  }

  fun allCloudAvailableOs(platform: Platform): List<String> {
    return CLOUD_CATALOG.filter { it.platform == platform }.map { it.os }.distinct()
  }

  fun defaultOs(platform: Platform): String = when (platform) {
    Platform.IOS -> DEFAULT_IOS_OS
    Platform.ANDROID -> DEFAULT_ANDROID_OS
    Platform.WEB -> DEFAULT_WEB_OS
  }
}
