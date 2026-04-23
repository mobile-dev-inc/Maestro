package maestro.cli.device

import maestro.cli.CliError
import maestro.cli.util.PrintUtils
import maestro.device.Device
import maestro.device.DeviceSpec
import maestro.device.Platform
import org.fusesource.jansi.Ansi.ansi

object PickDeviceView {

    fun showRunOnDevice(device: Device) {
        println("Running on ${device.description}")
    }

    fun pickDeviceToStart(devices: List<Device>): Device {
        val orderedDevices = printIndexedDevices(devices)

        println("Choose a device to boot and run on.")
        printEnterNumberPrompt()

        return pickIndex(orderedDevices)
    }

    fun requestDeviceOptions(platform: Platform? = null): DeviceSpec {
        PrintUtils.message("Please specify a device platform [android, ios, tvos, web]:")
        val selectedPlatform = platform
            ?: (readlnOrNull()?.lowercase()?.let {
                Platform.fromString(it)
            } ?: throw CliError("Please specify a platform"))

        return when (selectedPlatform) {
            Platform.ANDROID -> DeviceSpec.Android.DEFAULT
            Platform.IOS -> DeviceSpec.Ios.DEFAULT
            Platform.TVOS -> DeviceSpec.Tvos.DEFAULT
            Platform.WEB -> DeviceSpec.Web.DEFAULT
        }
    }

    fun pickRunningDevice(devices: List<Device>): Device {
        val orderedDevices = printIndexedDevices(devices)

        println("Multiple running devices detected. Choose a device to run on.")
        printEnterNumberPrompt()

        return pickIndex(orderedDevices)
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

    private fun printIndexedDevices(devices: List<Device>): List<Device> {
        val devicesByPlatform = devices.groupBy { it.platform }
        val orderedDevices = mutableListOf<Device>()
        var index = 0

        devicesByPlatform.forEach { (platform, platformDevices) ->
            println(platform.description)
            println()
            platformDevices.forEach { device ->
                orderedDevices.add(device)
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

        return orderedDevices
    }

}
