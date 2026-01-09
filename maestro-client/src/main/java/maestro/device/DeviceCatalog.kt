package maestro.device
import maestro.Platform
import maestro.locale.DeviceLocale

// TODO: Add more values to complete the device Specs
// Values to be added:
// - deviceOS
// - deviceModal
// - deviceOrientation
data class DeviceSpec(
  val locale: DeviceLocale
)

object DeviceCatalog {
    fun getDeviceSpecs(platform: Platform, locale: String): DeviceSpec {
        return DeviceSpec(
            locale = DeviceLocale.fromString(locale, platform)
        )
    }

    fun supportedSpecs(platform: Platform): List<DeviceSpec> {
        val allLocale = DeviceLocale.all(platform)
        return allLocale.map { DeviceSpec(locale = it) }
    }
}
