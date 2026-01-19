package maestro.cli.device

import maestro.Platform
import maestro.cli.CliError
import maestro.cli.util.PrintUtils
import maestro.device.Device
import maestro.device.DeviceCatalog
import maestro.device.DeviceSpec
import org.fusesource.jansi.Ansi.ansi

/**
 * Result of requesting device options from the user.
 * Contains both the selected platform and the complete device specification.
 */
data class DeviceSelectionResult(
    val platform: Platform,
    val deviceSpec: DeviceSpec
)

object PickDeviceView {

    fun showRunOnDevice(device: Device) {
        println("Running on ${device.description}")
    }

    fun pickDeviceToStart(devices: List<Device>): Device {
        printIndexedDevices(devices)

        println("Choose a device to boot and run on.")
        printEnterNumberPrompt()

        return pickIndex(devices)
    }

    fun requestDeviceOptions(platform: Platform? = null): DeviceSelectionResult {
        val selectedPlatform = if (platform == null) {
            PrintUtils.message("Please specify a device platform [android, ios, web]:")
            readlnOrNull()?.lowercase()?.let {
                when (it) {
                    "android" -> Platform.ANDROID
                    "ios" -> Platform.IOS
                    "web" -> Platform.WEB
                    else -> throw CliError("Unsupported platform: $it")
                }
            } ?: throw CliError("Please specify a platform")
        } else platform

        val maestroPlatform = when(selectedPlatform) {
            Platform.ANDROID -> maestro.Platform.ANDROID
            Platform.IOS -> maestro.Platform.IOS
            Platform.WEB -> maestro.Platform.WEB
        }
        
        val version = selectedPlatform.let {
            when (it) {
                Platform.IOS -> {
                    val supportedVersions = DeviceCatalog.getSupportedOSVersions(maestroPlatform)
                    val defaultVersion = DeviceCatalog.getDefaultOSVersion(maestroPlatform)
                    PrintUtils.message("Please specify iOS version $supportedVersions: Press ENTER for default ($defaultVersion)")
                    readlnOrNull()?.toIntOrNull() ?: defaultVersion.version
                }

                Platform.ANDROID -> {
                    val supportedVersions = DeviceCatalog.getSupportedOSVersions(maestroPlatform)
                    val defaultVersion = DeviceCatalog.getDefaultOSVersion(maestroPlatform)
                    PrintUtils.message("Please specify Android version $supportedVersions: Press ENTER for default ($defaultVersion)")
                    readlnOrNull()?.toIntOrNull() ?: defaultVersion.version
                }

                Platform.WEB -> 0
            }
        }

        val deviceSpec = DeviceCatalog.getDeviceSpecs(maestroPlatform, null, version, null)
        
        return DeviceSelectionResult(
            platform = selectedPlatform,
            deviceSpec = deviceSpec
        )
    }

    fun pickRunningDevice(devices: List<Device>): Device {
        printIndexedDevices(devices)

        println("Multiple running devices detected. Choose a device to run on.")
        printEnterNumberPrompt()

        return pickIndex(devices)
    }

    private fun <T> pickIndex(data: List<T>): T {
        println()
        while (!Thread.interrupted()) {
            val index = readlnOrNull()?.toIntOrNull() ?: 0

            if (index < 1 || index > data.size) {
                printEnterNumberPrompt()
                continue
            }

            return data[index - 1]
        }

        error("Interrupted")
    }

    private fun printEnterNumberPrompt() {
        println()
        println("Enter a number from the list above:")
    }

    private fun printIndexedDevices(devices: List<Device>) {
        val devicesByPlatform = devices.groupBy {
            it.platform
        }

        var index = 0

        devicesByPlatform.forEach { (platform, devices) ->
            println(platform.description)
            println()
            devices.forEach { device ->
                println(
                    ansi()
                        .render("[")
                        .fgCyan()
                        .render("${++index}")
                        .fgDefault()
                        .render("] ${device.description}")
                )
            }
            println()
        }
    }

}
