package maestro

import maestro.device.Platform
import maestro.locale.DeviceLocale

object DeviceCatalog {
  /**
   * Get device specifications
   * If parameters are not provided, defaults will be used based on the platform.
   */
  fun resolve(
    platform: Platform,
    deviceModel: String? = null,
    deviceOS: String? = null,
    locale: String? = null,
    deviceOrientation: DeviceOrientation? = null,
  ): MaestroDeviceConfiguration {
    return MaestroDeviceConfiguration(
      platform = platform,
      model = deviceModel ?: "iPhone 11",
      os = deviceOS ?: "18.5",
      locale = DeviceLocale.fromString(locale ?: "en_US", platform),
      orientation = deviceOrientation ?: DeviceOrientation.PORTRAIT,
    )
  }

  /**
   * Get all supported locales for a platform
   */
  fun getAllLocales(platform: Platform): List<DeviceLocale> {
    return DeviceLocale.all(platform)
  }

//  /**
//   * Gets all possible device specification combinations for a platform.
//   * @param platform The target platform
//   * @return List of all possible DeviceSpec combinations
//   */
//  fun getAllPossibleCombinations(platform: Platform): List<maestro.MaestroDeviceConfiguration> {
//    val allLocales = DeviceLocale.all(platform)
//    val allOrientations = DeviceOrientation.values().toList()
//
//    return buildList {
//      for (locale in allLocales) {
//        for (os in allOSVersions) {
//          for (model in allModels) {
//            for (orientation in allOrientations) {
//              add(
//                DeviceSpec(
//                  locale = locale,
//                  deviceOS = os,
//                  deviceModel = model,
//                  deviceOrientation = orientation
//                )
//              )
//            }
//          }
//        }
//      }
//    }
//  }
}



data class MaestroDeviceConfiguration(
  val platform: Platform,
  val model: String,
  val os: String,
  val locale: DeviceLocale,
  val orientation: DeviceOrientation,
) {
  /**
   * Generates the device name based on the model and OS version.
   * @param shardIndex Optional shard index to append to the device name (for parallel execution)
   * @return Device name suitable for simulator/emulator creation
   */
  fun generateDeviceName(shardIndex: Int? = null): String {
    val baseName = when (platform) {
      Platform.ANDROID -> {
        val modelName = model.replace(" ", "_")
        "Maestro_${modelName}_API_${os}"
      }
      Platform.IOS -> {
        val modelName = model.replace(" ", "")
        "Maestro_${modelName}_${os}"
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
