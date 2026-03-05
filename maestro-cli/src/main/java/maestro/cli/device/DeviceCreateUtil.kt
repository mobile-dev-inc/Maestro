package maestro.cli.device

import maestro.device.DeviceService
import maestro.device.Device
import maestro.device.Platform
import maestro.cli.CliError
import maestro.cli.util.*
import maestro.device.MaestroDeviceConfiguration


object DeviceCreateUtil {

    fun getOrCreateDevice(
        maestroDeviceConfiguration: MaestroDeviceConfiguration,
        forceCreate: Boolean = false,
        shardIndex: Int? = null,
    ): Device.AvailableForLaunch = when (maestroDeviceConfiguration) {
        is MaestroDeviceConfiguration.Android -> getOrCreateAndroidDevice(maestroDeviceConfiguration, forceCreate, shardIndex)
        is MaestroDeviceConfiguration.Ios     -> getOrCreateIosDevice(maestroDeviceConfiguration, forceCreate, shardIndex)
        is MaestroDeviceConfiguration.Web     -> Device.AvailableForLaunch(
            platform = Platform.WEB,
            description = "Chromium Desktop Browser (Experimental)",
            modelId = maestroDeviceConfiguration.deviceModel,
            deviceType = Device.DeviceType.BROWSER,
            deviceConfiguration = maestroDeviceConfiguration,
        )
    }

    fun getOrCreateIosDevice(
        config: MaestroDeviceConfiguration.Ios, forceCreate: Boolean, shardIndex: Int? = null
    ): Device.AvailableForLaunch {
        // check connected device
        if (DeviceService.isDeviceConnected(config.deviceName, Platform.IOS) != null && shardIndex == null && !forceCreate) {
            throw CliError("A device with name ${config.deviceName} is already connected")
        }

        // check existing device
        val existingDeviceId = DeviceService.isDeviceAvailableToLaunch(config.deviceName, Platform.IOS)?.let {
            if (forceCreate) {
                DeviceService.deleteIosDevice(it.modelId)
                null
            } else it.modelId
        }

        if (existingDeviceId != null) PrintUtils.message("Using existing device ${config.deviceName} (${existingDeviceId}).")
        else PrintUtils.message("Attempting to create iOS simulator: ${config.deviceName} ")

        val deviceUUID = try {
            existingDeviceId ?: DeviceService.createIosDevice(config.deviceName, config.deviceModel, config.deviceOs).toString()
        } catch (e: IllegalStateException) {
            val error = e.message ?: ""
            if (error.contains("Invalid runtime")) {
                val msg = """
                    Required runtime to create the simulator is not installed: ${config.deviceOs}

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
                throw CliError("Device type ${config.deviceModel} is either not supported or not found.")
            } else {
                throw CliError(error)
            }
        }

        if (existingDeviceId == null) PrintUtils.message("Created simulator with name ${config.deviceName} and UUID $deviceUUID")

        return Device.AvailableForLaunch(
            modelId = deviceUUID,
            description = config.deviceName,
            platform = Platform.IOS,
            deviceType = Device.DeviceType.SIMULATOR,
            deviceConfiguration = config,
        )
    }

    fun getOrCreateAndroidDevice(
        config: MaestroDeviceConfiguration.Android, forceCreate: Boolean, shardIndex: Int? = null
    ): Device.AvailableForLaunch {
        val systemImage = config.emulatorImage
        // check connected device
        if (DeviceService.isDeviceConnected(config.deviceName, Platform.ANDROID) != null && shardIndex == null && !forceCreate)
            throw CliError("A device with name ${config.deviceName} is already connected")

        // existing device
        val existingDevice =
            if (forceCreate) null
            else DeviceService.isDeviceAvailableToLaunch(config.deviceName, Platform.ANDROID)?.modelId

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

        if (existingDevice != null) PrintUtils.message("Using existing device ${config.deviceName}.")
        else PrintUtils.message("Attempting to create Android emulator: ${config.deviceName} ")

        val deviceLaunchId = try {
            existingDevice ?: DeviceService.createAndroidDevice(
                deviceName = config.deviceName,
                device = config.deviceModel,
                systemImage = systemImage,
                tag = config.tag,
                abi = config.cpuArchitecture.value,
                force = forceCreate,
            )
        } catch (e: IllegalStateException) {
            throw CliError("${e.message}")
        }

        if (existingDevice == null) PrintUtils.message("Created Android emulator: ${config.deviceName} ($systemImage)")

        return Device.AvailableForLaunch(
            modelId = deviceLaunchId,
            description = deviceLaunchId,
            platform = Platform.ANDROID,
            deviceType = Device.DeviceType.EMULATOR,
            deviceConfiguration = config,
        )
    }
}
