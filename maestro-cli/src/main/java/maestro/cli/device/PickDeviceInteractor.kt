package maestro.cli.device

import maestro.cli.CliError
import maestro.device.DeviceService
import maestro.device.DeviceService.withPlatform
import maestro.device.Device
import maestro.device.Platform
import maestro.cli.util.EnvUtils
import maestro.cli.util.PrintUtils

object PickDeviceInteractor {

    fun pickDevice(
        deviceId: String? = null,
        driverHostPort: Int? = null,
        platform: Platform? = null,
    ): Device.Connected {
        if (deviceId != null) {
            return DeviceService.listConnectedDevices()
                .find {
                    it.instanceId.equals(deviceId, ignoreCase = true)
                } ?: throw CliError("Device with id $deviceId is not connected")
        }

        return pickDeviceInternal(platform)
            .let { pickedDevice ->
                var result: Device = pickedDevice

                if (result is Device.AvailableForLaunch) {
                    when (result.platform) {
                        Platform.ANDROID -> PrintUtils.message("Launching Android emulator...")
                        Platform.IOS -> PrintUtils.message("Launching iOS simulator...")
                        Platform.WEB -> PrintUtils.message("Launching ${result.description}")
                    }

                    result = DeviceService.startDevice(result, driverHostPort)
                }

                if (result !is Device.Connected) {
                    error("Device $result is not connected")
                }

                result
            }
    }

    private fun pickDeviceInternal(platform: Platform?): Device {
        val connectedDevices = DeviceService.listConnectedDevices().withPlatform(platform)

        if (connectedDevices.size == 1) {
            val device = connectedDevices[0]

            PickDeviceView.showRunOnDevice(device)

            return device
        }

        if (connectedDevices.isEmpty()) {
            return startDevice(platform)
        }

        return pickRunningDevice(connectedDevices)
    }

    private fun startDevice(platform: Platform?): Device {
        if (EnvUtils.isWSL()) {
            throw CliError("No running emulator found. Start an emulator manually and try again.\nFor setup info checkout: https://maestro.mobile.dev/getting-started/installing-maestro/windows")
        }

        PrintUtils.message("No running devices found. Launch a device manually or select a number from the options below:\n")
        PrintUtils.message("[1] Start or create a Maestro recommended device\n[2] List existing devices\n[3] Quit")
        val input = readlnOrNull()?.lowercase()?.trim()

        when(input) {
            "1" -> {
                PrintUtils.clearConsole()
                val options = PickDeviceView.requestDeviceOptions(platform)
                if (options.platform == Platform.WEB) {
                    return Device.AvailableForLaunch(
                        platform = Platform.WEB,
                        description = "Chromium Desktop Browser (Experimental)",
                        modelId = "chromium",
                        language = null,
                        country = null,
                        deviceType = Device.DeviceType.BROWSER
                    )
                }
                return DeviceCreateUtil.getOrCreateDevice(options.platform, options.osVersion, null, null, options.forceCreate)
            }
            "2" -> {
                PrintUtils.clearConsole()
                val availableDevices = DeviceService.listAvailableForLaunchDevices().withPlatform(platform)
                if (availableDevices.isEmpty()) {
                    throw CliError("No devices available. To proceed, either install Android SDK or Xcode.")
                }

                return PickDeviceView.pickDeviceToStart(availableDevices)
            }
            else -> {
                throw CliError("Please either start a device manually or via running maestro start-device to proceed running your flows")
            }
        }
    }

    private fun pickRunningDevice(devices: List<Device>): Device {
        return PickDeviceView.pickRunningDevice(devices)
    }

}

fun main() {
    println(PickDeviceInteractor.pickDevice())

    println("Ready")
    while (!Thread.interrupted()) {
        Thread.sleep(1000)
    }
}
