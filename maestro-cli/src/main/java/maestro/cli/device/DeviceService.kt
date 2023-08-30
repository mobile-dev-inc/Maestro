package maestro.cli.device

import dadb.Dadb
import util.LocalSimulatorUtils
import util.LocalSimulatorUtils.SimctlError
import util.SimctlList
import maestro.cli.CliError
import maestro.cli.util.EnvUtils
import maestro.utils.MaestroTimer
import org.slf4j.LoggerFactory
import java.io.File

object DeviceService {
    val logger = LoggerFactory.getLogger(DeviceService::class.java)
    fun startDevice(device: Device.AvailableForLaunch): Device.Connected {
        when (device.platform) {
            Platform.IOS -> {
                try {
                    LocalSimulatorUtils.bootSimulator(device.modelId)
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

                val dadb = MaestroTimer.withTimeout(30000) {
                    try {
                        Dadb.discover()
                    } catch (ignored: Exception) {
                        Thread.sleep(100)
                        null
                    }
                } ?: throw CliError("Unable to start device: ${device.modelId}")

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
        return listOf(Device.AvailableForLaunch(
            platform = Platform.WEB,
            description = "Chromium Desktop Browser (Experimental)",
            modelId = "chromium"
        ))
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
        device: SimctlList.Device
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
            )
        }
    }

    private fun requireEmulatorBinary(): File {
        val androidHome = EnvUtils.androidHome()
            ?: throw CliError("Could not detect Android home environment variable is not set. Ensure that either ANDROID_HOME or ANDROID_SDK_ROOT is set.")
        val firstChoice = File(androidHome, "emulator/emulator")
        val secondChoice = File(androidHome, "tools/emulator")
        return firstChoice.takeIf { it.exists() } ?: secondChoice.takeIf { it.exists() }
        ?: throw CliError("Could not find emulator binary at either of the following paths:\n$firstChoice\n$secondChoice")
    }
}
