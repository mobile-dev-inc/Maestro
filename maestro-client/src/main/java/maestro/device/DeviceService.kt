package maestro.device

import dadb.Dadb
import dadb.adbserver.AdbServer
import maestro.device.DeviceError
import maestro.device.util.AndroidEnvUtils
import maestro.device.util.AvdDevice
import maestro.device.util.PrintUtils
import maestro.drivers.AndroidDriver
import maestro.utils.LocaleUtils
import maestro.utils.MaestroTimer
import okio.buffer
import okio.source
import org.slf4j.LoggerFactory
import java.io.File
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object DeviceService {
    private val logger = LoggerFactory.getLogger(DeviceService::class.java)
    fun startDevice(
        device: Device.AvailableForLaunch,
        driverHostPort: Int?,
        connectedDevices: Set<String> = setOf()
    ): Device.Connected {
        when (device.platform) {
            Platform.IOS -> {
                try {
                    util.LocalSimulatorUtils.bootSimulator(device.modelId)
                    if (device.language != null && device.country != null) {
                        PrintUtils.message("Setting the device locale to ${device.language}_${device.country}...")
                        util.LocalSimulatorUtils.setDeviceLanguage(device.modelId, device.language)
                        LocaleUtils.findIOSLocale(device.language, device.country)?.let {
                            util.LocalSimulatorUtils.setDeviceLocale(device.modelId, it)
                        }
                        util.LocalSimulatorUtils.reboot(device.modelId)
                    }
                    util.LocalSimulatorUtils.launchSimulator(device.modelId)
                    util.LocalSimulatorUtils.awaitLaunch(device.modelId)
                } catch (e: util.LocalSimulatorUtils.SimctlError) {
                    logger.error("Failed to launch simulator", e)
                    throw DeviceError(e.message)

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
                ).start().waitFor(7,TimeUnit.SECONDS)

                val dadb = MaestroTimer.withTimeout(60000) {
                    try {
                        Dadb.list().lastOrNull{ dadb ->
                            !connectedDevices.contains(dadb.toString())
                        }
                    } catch (ignored: Exception) {
                        Thread.sleep(100)
                        null
                    }
                } ?: throw DeviceError("Unable to start device: ${device.modelId}")

                PrintUtils.message("Waiting for emulator ( ${device.modelId} ) to boot...")
                while (!bootComplete(dadb)) {
                    Thread.sleep(1000)
                }

                if (device.language != null && device.country != null) {
                    PrintUtils.message("Setting the device locale to ${device.language}_${device.country}...")
                    val driver = AndroidDriver(dadb, driverHostPort)
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
                    description = "Chromium Web Browser",
                    platform = device.platform,
                )
            }
        }
    }

    fun listConnectedDevices(
        includeWeb: Boolean = false,
        host: String? = null,
        port: Int? = null,
    ): List<Device.Connected> {
        return listDevices(includeWeb = includeWeb, host, port)
            .filterIsInstance<Device.Connected>()
    }

    fun <T : Device> List<T>.withPlatform(platform: Platform?) =
        filter { platform == null || it.platform == platform }

    fun listAvailableForLaunchDevices(includeWeb: Boolean = false): List<Device.AvailableForLaunch> {
        return listDevices(includeWeb = includeWeb)
            .filterIsInstance<Device.AvailableForLaunch>()
    }

     fun listDevices(includeWeb: Boolean, host: String? = null, port: Int? = null): List<Device> {
        return listAndroidDevices(host, port) +
                listIOSDevices() +
                if (includeWeb) {
                    listWebDevices()
                } else {
                    listOf()
                }
    }

    private fun listWebDevices(): List<Device> {
        return listOf(
            Device.Connected(
                platform = Platform.WEB,
                description = "Chromium Web Browser",
                instanceId = "chromium"
            ),
            Device.AvailableForLaunch(
                modelId = "chromium",
                language = null,
                country = null,
                description = "Chromium Web Browser",
                platform = Platform.WEB
            )
        )
    }

    private fun listAndroidDevices(host: String? = null, port: Int? = null): List<Device> {
        val host = host ?: "localhost"
        if (port != null) {
            val dadb = Dadb.create(host, port)
            return listOf(
                Device.Connected(
                    instanceId = dadb.toString(),
                    description = dadb.toString(),
                    platform = Platform.ANDROID,
                )
            )
        }
        val connected = runCatching {
            Dadb.list(host = host).map { dadb ->
                val avdName = runCatching {
                    dadb.shell("getprop ro.kernel.qemu").output.trim().let { qemuProp ->
                        if (qemuProp == "1") {
                            val avdNameResult = ProcessBuilder("adb", "-s", dadb.toString(), "emu", "avd", "name")
                                .redirectErrorStream(true)
                                .start()
                                .apply { waitFor(5, TimeUnit.SECONDS) }
                                .inputStream.bufferedReader().readLine()?.trim() ?: ""

                            if (avdNameResult.isNotBlank() && !avdNameResult.contains("unknown AVD")) {
                                avdNameResult
                            } else null
                        } else null
                    }
                }.getOrNull()

                Device.Connected(
                    instanceId = dadb.toString(),
                    description = avdName ?: dadb.toString(),
                    platform = Platform.ANDROID,
                )
            }
        }.getOrNull() ?: emptyList()

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
            util.LocalSimulatorUtils.list()
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
        runtime: Map.Entry<String, List<util.SimctlList.Device>>,
        device: util.SimctlList.Device,
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
        return when (platform) {
            Platform.IOS -> listIOSDevices()
                .filterIsInstance<Device.Connected>()
                .find { it.description.contains(deviceName, ignoreCase = true) }

            else -> runCatching {
                (Dadb.list() + AdbServer.listDadbs(adbServerPort = 5038))
                    .mapNotNull { dadb -> runCatching { dadb.shell("getprop ro.kernel.qemu.avd_name").output }.getOrNull() }
                    .map { output ->
                        Device.Connected(
                            instanceId = output,
                            description = output,
                            platform = Platform.ANDROID
                        )
                    }
                    .find { connectedDevice -> connectedDevice.description.contains(deviceName, ignoreCase = true) }
            }.getOrNull()
        }
    }

    /**
     * @return true if ios simulator or android emulator is available to launch
     */
    fun isDeviceAvailableToLaunch(deviceName: String, platform: Platform): Device.AvailableForLaunch? {
        return if (platform == Platform.IOS) {
            listIOSDevices()
                .filterIsInstance<Device.AvailableForLaunch>()
                .find { it.description.contains(deviceName, ignoreCase = true) }
        } else {
            listAndroidDevices()
                .filterIsInstance<Device.AvailableForLaunch>()
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
        shardIndex: Int? = null,
    ): String {
        val avd = requireAvdManagerBinary()
        val name = "${deviceName}${"_${(shardIndex ?: 0) + 1}"}"
        val command = mutableListOf(
            avd.absolutePath,
            "create", "avd",
            "--name", name,
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

        return name
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

    fun killAndroidDevice(deviceId: String): Boolean {
        val command = listOf("adb", "-s", deviceId, "emu", "kill")

        try {
            val process = ProcessBuilder(*command.toTypedArray()).start()

            if (!process.waitFor(1, TimeUnit.MINUTES)) {
                throw TimeoutException("Android kill command timed out")
            }

            val success = process.exitValue() == 0
            if (success) {
                logger.info("Killed Android device: $deviceId")
            } else {
                logger.error("Failed to kill Android device: $deviceId")
            }

            return success
        } catch (e: Exception) {
            logger.error("Error killing Android device: $deviceId", e)
            return false
        }
    }

    fun killIOSDevice(deviceId: String): Boolean {
        val command = listOf("xcrun", "simctl", "shutdown", deviceId)

        try {
            val process = ProcessBuilder(*command.toTypedArray()).start()

            if (!process.waitFor(1, TimeUnit.MINUTES)) {
                throw TimeoutException("iOS kill command timed out")
            }

            val success = process.exitValue() == 0
            if (success) {
                logger.info("Killed iOS device: $deviceId")
            } else {
                logger.error("Failed to kill iOS device: $deviceId")
            }

            return success
        } catch (e: Exception) {
            logger.error("Error killing iOS device: $deviceId", e)
            return false
        }
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
