package maestro.cli.device

import maestro.cli.CliError
import maestro.cli.util.*

internal object DeviceCreateUtil {

    fun getOrCreateDevice(platform: Platform, osVersion: Int?, forceCreate: Boolean): Device.AvailableForLaunch {
        return when (platform) {
            Platform.ANDROID -> {
                getOrCreateAndroidDevice(osVersion, forceCreate)
            }

            Platform.IOS -> {
                getOrCreateIosDevice(osVersion, forceCreate)
            }

            else -> throw CliError("Unsupported platform $platform. Please specify one of: android, ios")
        }
    }

    private fun getOrCreateIosDevice(version: Int?, forceCreate: Boolean): Device.AvailableForLaunch {
        if (version !in DeviceConfigIos.versions) {
            throw CliError("Provided iOS version is not supported. Please use one of ${DeviceConfigIos.versions}")
        }

        val runtime = DeviceConfigIos.runtimes[version]
        if (runtime == null) {
            throw CliError("Provided iOS runtime is not supported $runtime")
        }

        val deviceName = DeviceConfigIos.generateDeviceName(version!!)
        val device = DeviceConfigIos.device

        // check connected device
        if (DeviceService.isDeviceConnected(deviceName, Platform.IOS) != null) {
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
            existingDeviceId ?: DeviceService.createIosDevice(deviceName, device, runtime).toString()
        } catch (e: IllegalStateException) {
            val error = e.message ?: ""
            if (error.contains("Invalid runtime")) {
                val msg = "Required runtime to create the simulator is not installed: $runtime\n\n" +
                        "To install additional iOS runtimes checkout this guide:\n" +
                        "* https://developer.apple.com/documentation/xcode/installing-additional-simulator-runtimes"
                throw CliError(msg)
            } else if (error.contains("Invalid device type")) {
                throw CliError("Device type $device is either not supported or not found.")
            } else {
                throw CliError(error)
            }
        }

        if (existingDeviceId == null) PrintUtils.message("Created simulator with name $deviceName and UUID $deviceUUID)")

        return Device.AvailableForLaunch(
            modelId = deviceUUID,
            description = deviceName,
            platform = Platform.IOS
        )

    }

    private fun getOrCreateAndroidDevice(version: Int?, forceCreate: Boolean): Device.AvailableForLaunch {
        if (version !in DeviceConfigAndroid.versions) {
            throw CliError("Provided Android version is not supported. Please use one of ${DeviceConfigAndroid.versions}")
        }

        val architecture = EnvUtils.getMacOSArchitecture()
        val pixels = DeviceService.getAvailablePixelDevices()
        val pixel = DeviceConfigAndroid.choosePixelDevice(pixels) ?: AvdDevice("-1", "Pixel 6", "pixel_6")

        val config = try {
            DeviceConfigAndroid.createConfig(version!!, pixel, architecture)
        } catch (e: IllegalStateException) {
            throw CliError(e.message ?: "Unable to create android device config")
        }

        val systemImage = config.systemImage
        val deviceName = config.deviceName

        // check connected device
        if (DeviceService.isDeviceConnected(deviceName, Platform.ANDROID) != null) {
            throw CliError("A device with name $deviceName is already connected")
        }

        // existing device
        val existingDevice =
            if (forceCreate) null else DeviceService.isDeviceAvailableToLaunch(deviceName, Platform.ANDROID)?.modelId

        // dependencies
        if (existingDevice == null && !DeviceService.isAndroidSystemImageInstalled(systemImage)) {
            PrintUtils.err("The required system image $systemImage is not installed.")

            PrintUtils.message("Would you like to install it? y/n")
            val r = readlnOrNull()?.lowercase()
            if (r == "y" || r == "yes") {
                PrintUtils.message("Attempting to install $systemImage via Android SDK Manager...\n")
                if (!DeviceService.installAndroidSystemImage(systemImage)) {
                    throw CliError(
                        "Unable to install required dependencies. You can install the system image manually by running this command:\n${
                            DeviceService.getAndroidSystemImageInstallCommand(
                                systemImage
                            )
                        }"
                    )
                }
            } else {
                throw CliError(
                    "To install the system image manually, you can run this command:\n${
                        DeviceService.getAndroidSystemImageInstallCommand(
                            systemImage
                        )
                    }"
                )
            }
        }

        if (existingDevice != null) PrintUtils.message("Using existing device $deviceName.")
        else PrintUtils.message("Attempting to create Android emulator: $deviceName ")

        val deviceLaunchId = try {
            existingDevice ?: DeviceService.createAndroidDevice(
                deviceName = config.deviceName,
                device = config.device,
                systemImage = config.systemImage,
                tag = config.tag,
                abi = config.abi,
                force = forceCreate
            )
        } catch (e: IllegalStateException) {
            throw CliError("${e.message}")
        }

        if (existingDevice == null) PrintUtils.message("Created Android emulator: $deviceName ($systemImage)")

        return Device.AvailableForLaunch(
            modelId = deviceLaunchId,
            description = deviceLaunchId,
            platform = Platform.ANDROID
        )
    }
}