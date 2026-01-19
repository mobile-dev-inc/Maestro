package maestro.cli.device

import maestro.device.DeviceService
import maestro.device.Device
import maestro.device.Platform
import maestro.device.DeviceSpec
import maestro.device.IosOS
import maestro.device.AndroidOS
import maestro.device.IosModel
import maestro.device.AndroidModel

import maestro.cli.CliError
import maestro.cli.util.*
import maestro.device.util.AvdDevice

/**
 * Android device configuration for creating emulators.
 * Contains all the necessary configuration to create an Android Virtual Device.
 */
internal data class AndroidDeviceConfig(
    val deviceName: String,
    val device: String,
    val tag: String,
    val systemImage: String,
    val abi: String
)

/**
 * CPU Architecture types for emulator/simulator creation.
 */
internal enum class CpuArchitecture {
    X86_64,
    ARM64,
    UNKNOWN
}

object DeviceCreateUtil {

    fun getOrCreateDevice(
        platform: Platform,
        deviceSpec: DeviceSpec,
        forceCreate: Boolean = false,
        shardIndex: Int? = null,
    ): Device.AvailableForLaunch = when (platform) {
        Platform.ANDROID -> getOrCreateAndroidDevice(deviceSpec, forceCreate, shardIndex)
        Platform.IOS -> getOrCreateIosDevice(deviceSpec, forceCreate, shardIndex)
        else -> throw CliError("Unsupported platform $platform. Please specify one of: android, ios")
    }
    
    /**
     * Creates Android emulator configuration including system image and ABI based on architecture.
     * This is CLI-specific logic that depends on the host system architecture.
     */
    private fun createAndroidEmulatorConfig(
        apiLevel: Int,
        device: AvdDevice,
        architecture: CpuArchitecture
    ): AndroidDeviceConfig {
        val tag = "google_apis"
        val systemImage = when (architecture) {
            CpuArchitecture.X86_64 -> "x86_64"
            CpuArchitecture.ARM64 -> "arm64-v8a"
            else -> throw IllegalStateException("Unsupported architecture $architecture")
        }.let {
            "system-images;android-$apiLevel;google_apis;$it"
        }
        val abi = when (architecture) {
            CpuArchitecture.X86_64 -> "x86_64"
            CpuArchitecture.ARM64 -> "arm64-v8a"
            else -> throw IllegalStateException("Unsupported architecture $architecture")
        }

        return AndroidDeviceConfig(
            deviceName = "", // Not used - we generate name via DeviceSpec
            device = device.nameId,
            tag = tag,
            systemImage = systemImage,
            abi = abi
        )
    }

    fun getOrCreateIosDevice(
        deviceSpec: DeviceSpec, forceCreate: Boolean, shardIndex: Int? = null
    ): Device.AvailableForLaunch {
        // Safe cast - validation already done in DeviceCatalog.getDeviceSpecs()
        val iosOS = deviceSpec.deviceOS as IosOS
        val iosModel = deviceSpec.deviceModel as IosModel
        
        val version = iosOS.version
        val runtime = iosOS.runtime

        val deviceName = deviceSpec.generateDeviceName(shardIndex)
        val device = iosModel.modelId

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
            existingDeviceId ?: DeviceService.createIosDevice(deviceName, device, runtime).toString()
        } catch (e: IllegalStateException) {
            val error = e.message ?: ""
            if (error.contains("Invalid runtime")) {
                val msg = """
                    Required runtime to create the simulator is not installed: $runtime
                    
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
                throw CliError("Device type $device is either not supported or not found.")
            } else {
                throw CliError(error)
            }
        }

        if (existingDeviceId == null) PrintUtils.message("Created simulator with name $deviceName and UUID $deviceUUID")

        return Device.AvailableForLaunch(
            modelId = deviceUUID,
            description = deviceName,
            platform = Platform.IOS,
            language = deviceSpec.locale.languageCode,
            country = deviceSpec.locale.countryCode,
            deviceType = Device.DeviceType.SIMULATOR
        )

    }

    fun getOrCreateAndroidDevice(
        deviceSpec: DeviceSpec, forceCreate: Boolean, shardIndex: Int? = null
    ): Device.AvailableForLaunch {
        // Safe cast - validation already done in DeviceCatalog.getDeviceSpecs()
        val androidOS = deviceSpec.deviceOS as AndroidOS
        val androidModel = deviceSpec.deviceModel as AndroidModel
        
        val version = androidOS.version

        val cpuArch = EnvUtils.getMacOSArchitecture()
        val architecture = when (cpuArch) {
            CPU_ARCHITECTURE.x86_64 -> CpuArchitecture.X86_64
            CPU_ARCHITECTURE.ARM64 -> CpuArchitecture.ARM64
            else -> CpuArchitecture.UNKNOWN
        }
        
        val pixels = DeviceService.getAvailablePixelDevices()
        val pixel = AndroidModel.chooseBestPixel(pixels) { it.nameId } ?: 
            AvdDevice("-1", androidModel.modelId, androidModel.displayName)

        val config = try {
            createAndroidEmulatorConfig(version, pixel, architecture)
        } catch (e: IllegalStateException) {
            throw CliError(e.message ?: "Unable to create android device config")
        }

        val systemImage = config.systemImage
        val deviceName = deviceSpec.generateDeviceName(shardIndex)

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
                deviceName = deviceName,
                device = config.device,
                systemImage = config.systemImage,
                tag = config.tag,
                abi = config.abi,
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
            language = deviceSpec.locale.languageCode,
            country = deviceSpec.locale.countryCode,
            deviceType = Device.DeviceType.EMULATOR,
        )
    }
}
