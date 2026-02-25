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
            modelId = maestroDeviceConfiguration.browser,
            language = null,
            country = null,
            deviceType = Device.DeviceType.BROWSER,
        )
    }

    fun getOrCreateIosDevice(
        config: MaestroDeviceConfiguration.Ios, forceCreate: Boolean, shardIndex: Int? = null
    ): Device.AvailableForLaunch {
        val deviceName = config.generateDeviceName()

        // check connected device
        if (DeviceService.isDeviceConnected(deviceName, Platform.IOS) != null && shardIndex == null && !forceCreate) {
            throw CliError("A device with name $deviceName is already connected")
        }

        // check existing device
        val existingDeviceId = DeviceService.isDeviceAvailableToLaunch(deviceName, Platform.IOS)?.let {
            if (forceCreate) {
                DeviceService.deleteIosDevice(it.modelId)
                null
            } else it.modelId
        }

        if (existingDeviceId != null) PrintUtils.message("Using existing device $deviceName (${existingDeviceId}).")
        else PrintUtils.message("Attempting to create iOS simulator: $deviceName ")

        val deviceUUID = try {
            existingDeviceId ?: DeviceService.createIosDevice(deviceName, config.deviceModel, config.deviceOs).toString()
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

        if (existingDeviceId == null) PrintUtils.message("Created simulator with name $deviceName and UUID $deviceUUID")

        return Device.AvailableForLaunch(
            modelId = deviceUUID,
            description = deviceName,
            platform = Platform.IOS,
            language = config.locale.languageCode,
            country = config.locale.countryCode,
            deviceType = Device.DeviceType.SIMULATOR
        )
    }

    fun getOrCreateAndroidDevice(
        config: MaestroDeviceConfiguration.Android, forceCreate: Boolean, shardIndex: Int? = null
    ): Device.AvailableForLaunch {
        val abi = when (val architecture = EnvUtils.getMacOSArchitecture()) {
            CPU_ARCHITECTURE.x86_64 -> "x86_64"
            CPU_ARCHITECTURE.ARM64 -> "arm64-v8a"
            else -> throw CliError("Unsupported architecture: $architecture")
        }
        val tag = "google_apis"
        val systemImage = config.emulatorImage
        val deviceName = config.generateDeviceName()

        // check connected device
        if (DeviceService.isDeviceConnected(deviceName, Platform.ANDROID) != null && shardIndex == null && !forceCreate)
            throw CliError("A device with name $deviceName is already connected")

        // existing device
        val existingDevice =
            if (forceCreate) null
            else DeviceService.isDeviceAvailableToLaunch(deviceName, Platform.ANDROID)?.modelId

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

        if (existingDevice != null) PrintUtils.message("Using existing device $deviceName.")
        else PrintUtils.message("Attempting to create Android emulator: $deviceName ")

        val deviceLaunchId = try {
            existingDevice ?: DeviceService.createAndroidDevice(
                deviceName = config.generateDeviceName(shardIndex),
                device = config.deviceModel,
                systemImage = systemImage,
                tag = tag,
                abi = abi,
                force = forceCreate,
                shardIndex = shardIndex,
            )
        } catch (e: IllegalStateException) {
            throw CliError("${e.message}")
        }

        if (existingDevice == null) PrintUtils.message("Created Android emulator: $deviceName ($systemImage)")

        return Device.AvailableForLaunch(
            modelId = deviceLaunchId,
            description = deviceLaunchId,
            platform = Platform.ANDROID,
            language = config.locale.languageCode,
            country = config.locale.countryCode,
            deviceType = Device.DeviceType.EMULATOR,
        )
    }
}
