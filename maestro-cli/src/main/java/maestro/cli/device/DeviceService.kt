package maestro.cli.device

import dadb.Dadb
import maestro.cli.CliError
import maestro.cli.util.AndroidEnvUtils
import maestro.cli.util.AvdDevice
import maestro.cli.util.PrintUtils
import maestro.drivers.AndroidDriver
import maestro.utils.LocaleUtils
import maestro.utils.MaestroTimer
import okio.buffer
import okio.source
import org.slf4j.LoggerFactory
import util.LocalSimulatorUtils
import util.LocalSimulatorUtils.SimctlError
import util.SimctlList
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object DeviceService {
    val logger = LoggerFactory.getLogger(DeviceService::class.java)
    fun startDevice(device: Device.AvailableForLaunch): Device.Connected {
        when (device.platform) {
            Platform.IOS -> {
                try {
                    LocalSimulatorUtils.bootSimulator(device.modelId)
                    if (device.language != null && device.country != null) {
                        PrintUtils.message("Setting the device locale to ${device.language}_${device.country}...")
                        LocalSimulatorUtils.setDeviceLanguage(device.modelId, device.language)
                        LocaleUtils.findIOSLocale(device.language, device.country)?.let {
                            LocalSimulatorUtils.setDeviceLocale(device.modelId, it)
                        }
                        LocalSimulatorUtils.reboot(device.modelId)
                    }
                    LocalSimulatorUtils.launchSimulator(device.modelId)
                    LocalSimulatorUtils.awaitLaunch(device.modelId)
                } catch (e: SimctlError) {
                    logger.error("Failed to launch simulator", e)
                    throw CliError(e.message)
                }

                return Device.Connected(
                    instanceId = device.modelId,
                    description = device.description,
                    platform = device.platform,
                )
            }

            Platform.ANDROID -> {
                val emulatorBinary = requireEmulatorBinary()

                ProcessBuilder(
                    emulatorBinary.absolutePath,
                    "-avd",
                    device.modelId,
                    "-netdelay",
                    "none",
                    "-netspeed",
                    "full"
                ).start()

                val dadb = MaestroTimer.withTimeout(60000) {
                    try {
                        Dadb.discover()
                    } catch (ignored: Exception) {
                        Thread.sleep(100)
                        null
                    }
                } ?: throw CliError("Unable to start device: ${device.modelId}")

                PrintUtils.message("Waiting for emulator to boot...")
                while (!bootComplete(dadb)) {
                    Thread.sleep(1000)
                }

                if (device.language != null && device.country != null) {
                    PrintUtils.message("Setting the device locale to ${device.language}_${device.country}...")
                    val driver = AndroidDriver(dadb)
                    driver.installMaestroDriverApp()
                    val result = driver.setDeviceLocale(
                        country = device.country,
                        language = device.language
                    )

                    when (result) {
                        SET_LOCALE_RESULT_SUCCESS -> PrintUtils.message("[Done] Setting the device locale to ${device.language}_${device.country}")
                        SET_LOCALE_RESULT_LOCALE_NOT_VALID -> throw IllegalStateException("Failed to set locale ${device.language}_${device.country}, the locale is not valid for a chosen device")
                        SET_LOCALE_RESULT_UPDATE_CONFIGURATION_FAILED -> throw IllegalStateException("Failed to set locale ${device.language}_${device.country}, exception during updating configuration occurred")
                        else -> throw IllegalStateException("Failed to set locale ${device.language}_${device.country}, unknown exception happened")
                    }
                    driver.uninstallMaestroDriverApp()
                }

                return Device.Connected(
                    instanceId = dadb.toString(),
                    description = device.description,
                    platform = device.platform,
                )
            }

            Platform.WEB -> {
                return Device.Connected(
                    instanceId = "",
                    description = "Selenium Chromium driver",
                    platform = device.platform,
                )
            }
        }
    }

    fun listConnectedDevices(): List<Device.Connected> {
        return listDevices()
            .filterIsInstance(Device.Connected::class.java)
    }

    fun listAvailableForLaunchDevices(): List<Device.AvailableForLaunch> {
        return listDevices()
            .filterIsInstance(Device.AvailableForLaunch::class.java)
    }

    private fun listDevices(): List<Device> {
        return listAndroidDevices() + listIOSDevices() + listWebDevices()
    }

    private fun listWebDevices(): List<Device> {
        return listOf(
            Device.AvailableForLaunch(
                platform = Platform.WEB,
                description = "Chromium Desktop Browser (Experimental)",
                modelId = "chromium",
                language = null,
                country = null,
            )
        )
    }

    private fun listAndroidDevices(): List<Device> {
        val connected = Dadb.list()
            .map {
                Device.Connected(
                    instanceId = it.toString(),
                    description = it.toString(),
                    platform = Platform.ANDROID,
                )
            }

        // Note that there is a possibility that AVD is actually already connected and is present in
        // connectedDevices.
        val avds = try {
            val emulatorBinary = requireEmulatorBinary()
            ProcessBuilder(emulatorBinary.absolutePath, "-list-avds")
                .start()
                .inputStream
                .bufferedReader()
                .useLines { lines ->
                    lines
                        .map {
                            Device.AvailableForLaunch(
                                modelId = it,
                                description = it,
                                platform = Platform.ANDROID,
                                language = null,
                                country = null,
                            )
                        }
                        .toList()
                }
        } catch (ignored: Exception) {
            emptyList()
        }

        return connected + avds
    }

    private fun listIOSDevices(): List<Device> {
        val simctlList = try {
            LocalSimulatorUtils.list()
        } catch (ignored: Exception) {
            return emptyList()
        }

        val runtimeNameByIdentifier = simctlList
            .runtimes
            .associate { it.identifier to it.name }

        return simctlList
            .devices
            .flatMap { runtime ->
                runtime.value
                    .filter { it.isAvailable }
                    .map { device(runtimeNameByIdentifier, runtime, it) }
            }
    }

    private fun device(
        runtimeNameByIdentifier: Map<String, String>,
        runtime: Map.Entry<String, List<SimctlList.Device>>,
        device: SimctlList.Device,
    ): Device {
        val runtimeName = runtimeNameByIdentifier[runtime.key] ?: "Unknown runtime"
        val description = "${device.name} - $runtimeName - ${device.udid}"

        return if (device.state == "Booted") {
            Device.Connected(
                instanceId = device.udid,
                description = description,
                platform = Platform.IOS,
            )
        } else {
            Device.AvailableForLaunch(
                modelId = device.udid,
                description = description,
                platform = Platform.IOS,
                language = null,
                country = null,
            )
        }
    }

    /**
     * @return true if ios simulator or android emulator is currently connected
     */
    fun isDeviceConnected(deviceName: String, platform: Platform): Device.Connected? {
        return if (platform == Platform.IOS) {
            listIOSDevices()
                .filterIsInstance(Device.Connected::class.java)
                .find { it.description.contains(deviceName, ignoreCase = true) }
        } else {
            Dadb.list()
                .mapNotNull {
                    runCatching { it.shell("getprop ro.kernel.qemu.avd_name").output }.getOrNull() // Gets AVD name
                }
                .map {
                    Device.Connected(
                        instanceId = it,
                        description = it,
                        platform = Platform.ANDROID,
                    )
                }
                .find { it.description.contains(deviceName, ignoreCase = true) }
        }
    }

    /**
     * @return true if ios simulator or android emulator is available to launch
     */
    fun isDeviceAvailableToLaunch(deviceName: String, platform: Platform): Device.AvailableForLaunch? {
        return if (platform == Platform.IOS) {
            listIOSDevices()
                .filterIsInstance(Device.AvailableForLaunch::class.java)
                .find { it.description.contains(deviceName, ignoreCase = true) }
        } else {
            listAndroidDevices()
                .filterIsInstance(Device.AvailableForLaunch::class.java)
                .find { it.description.contains(deviceName, ignoreCase = true) }
        }
    }

    /**
     * Creates an iOS simulator
     *
     * @param deviceName Any name
     * @param device Simulator type as specified by Apple i.e. iPhone-11
     * @param os OS runtime name as specified by Apple i.e. iOS-16-2
     */
    fun createIosDevice(deviceName: String, device: String, os: String): UUID {
        val command = listOf(
            "xcrun",
            "simctl",
            "create",
            deviceName,
            "com.apple.CoreSimulator.SimDeviceType.$device",
            "com.apple.CoreSimulator.SimRuntime.$os"
        )

        val process = ProcessBuilder(*command.toTypedArray()).start()
        if (!process.waitFor(5, TimeUnit.MINUTES)) {
            throw TimeoutException()
        }

        if (process.exitValue() != 0) {
            val processOutput = process.errorStream
                .source()
                .buffer()
                .readUtf8()

            throw IllegalStateException(processOutput)
        } else {
            val output = String(process.inputStream.readBytes()).trim()
            return try {
                UUID.fromString(output)
            } catch (ignore: IllegalArgumentException) {
                throw IllegalStateException("Unable to create device. No UUID was generated")
            }
        }
    }

    /**
     * Creates an Android emulator
     *
     * @param deviceName Any device name
     * @param device Device type as specified by the Android SDK i.e. "pixel_6"
     * @param systemImage Full system package i.e "system-images;android-28;google_apis;x86_64"
     * @param tag google apis or playstore tag i.e. google_apis or google_apis_playstore
     * @param abi x86_64, x86, arm64 etc..
     */
    fun createAndroidDevice(
        deviceName: String,
        device: String,
        systemImage: String,
        tag: String,
        abi: String,
        force: Boolean = false,
    ): String {
        val avd = requireAvdManagerBinary()
        val command = mutableListOf(
            avd.absolutePath,
            "create", "avd",
            "--name", deviceName,
            "--package", systemImage,
            "--tag", tag,
            "--abi", abi,
            "--device", device,
        )

        if (force) command.add("--force")

        val process = ProcessBuilder(*command.toTypedArray()).start()

        if (!process.waitFor(5, TimeUnit.MINUTES)) {
            throw TimeoutException()
        }

        if (process.exitValue() != 0) {
            val processOutput = process.errorStream
                .source()
                .buffer()
                .readUtf8()

            throw IllegalStateException("Failed to start android emulator: $processOutput")
        }

        return deviceName
    }

    fun getAvailablePixelDevices(): List<AvdDevice> {
        val avd = requireAvdManagerBinary()
        val command = mutableListOf(
            avd.absolutePath,
            "list", "device"
        )

        val process = ProcessBuilder(*command.toTypedArray()).start()

        if (!process.waitFor(1, TimeUnit.MINUTES)) {
            throw TimeoutException()
        }

        if (process.exitValue() != 0) {
            val processOutput = process.errorStream
                .source()
                .buffer()
                .readUtf8()

            throw IllegalStateException("Failed to list avd devices emulator: $processOutput")
        }

        return runCatching {
            AndroidEnvUtils.parsePixelDevices(String(process.inputStream.readBytes()).trim())
        }.getOrNull() ?: emptyList()
    }

    /**
     * @return true is Android system image is already installed
     */
    fun isAndroidSystemImageInstalled(image: String): Boolean {
        val command = listOf(
            requireSdkManagerBinary().absolutePath,
            "--list_installed"
        )
        try {
            val process = ProcessBuilder(*command.toTypedArray()).start()
            if (!process.waitFor(1, TimeUnit.MINUTES)) {
                throw TimeoutException()
            }

            if (process.exitValue() == 0) {
                val output = String(process.inputStream.readBytes()).trim()

                return output.contains(image)
            }
        } catch (e: Exception) {
            logger.error("Unable to detect if SDK package is installed", e)
        }

        return false
    }

    /**
     * Uses the Android SDK manager to install android image
     */
    fun installAndroidSystemImage(image: String): Boolean {
        val command = listOf(
            requireSdkManagerBinary().absolutePath,
            image
        )
        try {
            val process = ProcessBuilder(*command.toTypedArray())
                .inheritIO()
                .start()
            if (!process.waitFor(120, TimeUnit.MINUTES)) {
                throw TimeoutException()
            }

            if (process.exitValue() == 0) {
                val output = String(process.inputStream.readBytes()).trim()

                return output.contains(image)
            }
        } catch (e: Exception) {
            logger.error("Unable to install if SDK package is installed", e)
        }

        return false
    }

    fun getAndroidSystemImageInstallCommand(pkg: String): String {
        return listOf(
            requireSdkManagerBinary().absolutePath,
            "\"$pkg\""
        ).joinToString(separator = " ")
    }

    fun deleteIosDevice(uuid: String): Boolean {
        val command = listOf(
            "xcrun",
            "simctl",
            "delete",
            uuid
        )

        val process = ProcessBuilder(*command.toTypedArray()).start()

        if (!process.waitFor(1, TimeUnit.MINUTES)) {
            throw TimeoutException()
        }

        return process.exitValue() == 0
    }

    private fun bootComplete(dadb: Dadb): Boolean {
        return try {
            val booted = dadb.shell("getprop sys.boot_completed").output.trim() == "1"
            val settingsAvailable = dadb.shell("settings list global").exitCode == 0
            val packageManagerAvailable = dadb.shell("pm get-max-users").exitCode == 0
            return settingsAvailable && packageManagerAvailable && booted
        } catch (e: IllegalStateException) {
            false
        }
    }

    private fun requireEmulatorBinary(): File = AndroidEnvUtils.requireEmulatorBinary()

    private fun requireAvdManagerBinary(): File = AndroidEnvUtils.requireCommandLineTools("avdmanager")

    private fun requireSdkManagerBinary(): File = AndroidEnvUtils.requireCommandLineTools("sdkmanager")

    private const val SET_LOCALE_RESULT_SUCCESS = 0
    private const val SET_LOCALE_RESULT_LOCALE_NOT_VALID = 1
    private const val SET_LOCALE_RESULT_UPDATE_CONFIGURATION_FAILED = 2
}
