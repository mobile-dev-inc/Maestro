package maestro.cli.device

import maestro.cli.CliError
import maestro.cli.model.DeviceStartOptions
import maestro.cli.util.DeviceConfigAndroid
import maestro.cli.util.DeviceConfigIos
import maestro.cli.util.PrintUtils
import maestro.device.Device
import maestro.device.Platform
import org.fusesource.jansi.Ansi.ansi

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

    fun requestDeviceOptions(platform: Platform? = null): DeviceStartOptions {
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

        val version = selectedPlatform.let {
            when (it) {
                Platform.IOS -> {
                    PrintUtils.message("Please specify iOS version ${DeviceConfigIos.versions}: Press ENTER for default (${DeviceConfigIos.defaultVersion})")
                    readlnOrNull()?.toIntOrNull() ?: DeviceConfigIos.defaultVersion
                }

                Platform.ANDROID -> {
                    PrintUtils.message("Please specify Android version ${DeviceConfigAndroid.versions}: Press ENTER for default (${DeviceConfigAndroid.defaultVersion})")
                    readlnOrNull()?.toIntOrNull() ?: DeviceConfigAndroid.defaultVersion
                }

                Platform.WEB -> 0
            }
        }

        return DeviceStartOptions(
            platform = selectedPlatform,
            osVersion = version,
            forceCreate = false
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
