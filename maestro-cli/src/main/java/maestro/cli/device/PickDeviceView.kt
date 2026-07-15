package maestro.cli.device

import maestro.cli.CliError
import maestro.cli.util.PrintUtils
import maestro.device.Device
import maestro.device.DeviceSpec
import maestro.device.Platform
import org.jline.jansi.Ansi.ansi

object PickDeviceView {

    fun showRunOnDevice(device: Device) {
        println("Running on ${device.description}")
    }

    fun pickDeviceToStart(devices: List<Device>): Device {
        printIndexedDevices(devices)

        println("Choose a device to boot and run on.")
        printEnterNumberPrompt()
        println()

        return pickIndex(devices, ::printEnterNumberPrompt)
    }

    fun requestDeviceOptions(platform: Platform? = null): DeviceSpec {
        PrintUtils.message("Please specify a device platform [android, ios, web]:")
        val selectedPlatform = platform
            ?: (readlnOrNull()?.lowercase()?.let {
                Platform.fromString(it)
            } ?: throw CliError("Please specify a platform"))

        return when (selectedPlatform) {
            Platform.ANDROID -> DeviceSpec.Android.DEFAULT
            Platform.IOS -> DeviceSpec.Ios.DEFAULT
            Platform.WEB -> DeviceSpec.Web.DEFAULT
        }
    }

    fun pickRunningDevice(devices: List<Device>): Device {
        println("There are multiple connected devices")
        printConnectedDevices(devices)
        printRunningDevicePrompt()

        return pickIndex(
            data = devices,
            prompt = ::printRunningDevicePrompt,
            allowQuit = true,
        )
    }

    private fun <T> pickIndex(
        data: List<T>,
        prompt: () -> Unit,
        allowQuit: Boolean = false,
    ): T {
        while (!Thread.interrupted()) {
            val input = readlnOrNull()?.trim()
            if (allowQuit && input.equals("q", ignoreCase = true)) {
                throw CliError("Device selection was cancelled")
            }

            val index = input?.toIntOrNull() ?: 0

            if (index < 1 || index > data.size) {
                prompt()
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

    private fun printRunningDevicePrompt() {
        print("Please choose one (or \"q\" to quit): ")
        System.out.flush()
    }

    private fun printConnectedDevices(devices: List<Device>) {
        devices.forEachIndexed { index, device ->
            println(
                ansi()
                    .render("[")
                    .fgCyan()
                    .render("${index + 1}")
                    .fgDefault()
                    .render("]: ${device.description}")
            )
        }
        println()
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
