package maestro.cli.device

import maestro.device.DeviceService
import maestro.device.Device
import maestro.device.Platform
import maestro.cli.CliError
import maestro.cli.util.*
import maestro.device.DeviceSpec

object DeviceCreateUtil {

    fun getOrCreateDevice(
        deviceSpec: DeviceSpec,
        forceCreate: Boolean = false,
        shardIndex: Int? = null,
    ): Device.AvailableForLaunch = when (deviceSpec) {
        is DeviceSpec.Android -> getOrCreateAndroidDevice(deviceSpec, forceCreate, shardIndex)
        is DeviceSpec.Ios     -> getOrCreateIosDevice(deviceSpec, forceCreate, shardIndex)
        is DeviceSpec.Web     -> Device.AvailableForLaunch(
            platform = Platform.WEB,
            description = "Chromium Desktop Browser (Experimental)",
            modelId = deviceSpec.model,
            deviceType = Device.DeviceType.BROWSER,
            deviceSpec = deviceSpec,
        )
    }

    fun getOrCreateIosDevice(
        deviceSpec: DeviceSpec.Ios, forceCreate: Boolean, shardIndex: Int? = null
    ): Device.AvailableForLaunch {
        // check connected device
        if (DeviceService.isDeviceConnected(deviceSpec.deviceName, Platform.IOS) != null && shardIndex == null && !forceCreate) {
            throw CliError("A device with name ${deviceSpec.deviceName} is already connected")
        }

        // check existing device
        val existingDeviceId = DeviceService.isDeviceAvailableToLaunch(deviceSpec.deviceName, Platform.IOS)?.let {
            if (forceCreate) {
                DeviceService.deleteIosDevice(it.modelId)
                null
            } else it.modelId
        }

        if (existingDeviceId != null) PrintUtils.message("Using existing device ${deviceSpec.deviceName} (${existingDeviceId}).")
        else PrintUtils.message("Attempting to create iOS simulator: ${deviceSpec.deviceName} ")

        val deviceUUID = try {
            existingDeviceId ?: DeviceService.createIosDevice(deviceSpec.deviceName, deviceSpec.model, deviceSpec.os).toString()
        } catch (e: IllegalStateException) {
            val error = e.message ?: ""
            if (error.contains("Invalid runtime")) {
                val msg = """
                    Required runtime to create the simulator is not installed: ${deviceSpec.os}

                    To install additional iOS runtimes checkout this guide:
                    * https://developer.apple.com/documentation/xcode/installing-additional-simulator-runtimes
                """.trimIndent()
                throw CliError(msg)
            } else if (error.contains("xcrun: error: unable to find utility \"simctl\"")) {
                val msg = """
                    The xcode-select CLI tools are not installed, install with xcode-select --install

                    If the xcode-select CLI tools are already installed, the path may be broken. Try
                    running sudo xcode-select -r to repair the path and re-run this command
                """.trimIndent()
                throw CliError(msg)
            } else if (error.contains("Invalid device type")) {
                throw CliError("Device type ${deviceSpec.model} is either not supported or not found.")
            } else {
                throw CliError(error)
            }
        }

        if (existingDeviceId == null) PrintUtils.message("Created simulator with name ${deviceSpec.deviceName} and UUID $deviceUUID")

        return Device.AvailableForLaunch(
            modelId = deviceUUID,
            description = deviceSpec.deviceName,
            platform = Platform.IOS,
            deviceType = Device.DeviceType.SIMULATOR,
            deviceSpec = deviceSpec,
        )
    }

    fun getOrCreateAndroidDevice(
        deviceSpec: DeviceSpec.Android, forceCreate: Boolean, shardIndex: Int? = null
    ): Device.AvailableForLaunch {
        val systemImage = deviceSpec.emulatorImage
        // check connected device
        if (DeviceService.isDeviceConnected(deviceSpec.deviceName, Platform.ANDROID) != null && shardIndex == null && !forceCreate)
            throw CliError("A device with name ${deviceSpec.deviceName} is already connected")

        // existing device
        val existingDevice =
            if (forceCreate) null
            else DeviceService.isDeviceAvailableToLaunch(deviceSpec.deviceName, Platform.ANDROID)?.modelId

        // dependencies
        if (existingDevice == null && !DeviceService.isAndroidSystemImageInstalled(systemImage)) {
            PrintUtils.err("The required system image $systemImage is not installed.")

            PrintUtils.message("Would you like to install it? y/n")
            val r = readlnOrNull()?.lowercase()
            if (r == "y" || r == "yes") {
                PrintUtils.message("Attempting to install $systemImage via Android SDK Manager...\n")
                if (!DeviceService.installAndroidSystemImage(systemImage)) {
                    val message = """
                        Unable to install required dependencies. You can install the system image manually by running this command:
                        ${DeviceService.getAndroidSystemImageInstallCommand(systemImage)}
                    """.trimIndent()
                    throw CliError(message)
                }
            } else {
                val message = """
                    To install the system image manually, you can run this command:
                    ${DeviceService.getAndroidSystemImageInstallCommand(systemImage)}
                """.trimIndent()
                throw CliError(message)
            }
        }

        if (existingDevice != null) PrintUtils.message("Using existing device ${deviceSpec.deviceName}.")
        else PrintUtils.message("Attempting to create Android emulator: ${deviceSpec.deviceName} ")

        val deviceLaunchId = try {
            existingDevice ?: DeviceService.createAndroidDevice(
                deviceName = deviceSpec.deviceName,
                device = deviceSpec.model,
                systemImage = systemImage,
                tag = deviceSpec.tag,
                abi = deviceSpec.cpuArchitecture.value,
                force = forceCreate,
            )
        } catch (e: IllegalStateException) {
            throw CliError("${e.message}")
        }

        if (existingDevice == null) PrintUtils.message("Created Android emulator: ${deviceSpec.deviceName} ($systemImage)")

        return Device.AvailableForLaunch(
            modelId = deviceLaunchId,
            description = deviceLaunchId,
            platform = Platform.ANDROID,
            deviceType = Device.DeviceType.EMULATOR,
            deviceSpec = deviceSpec,
        )
    }
}
