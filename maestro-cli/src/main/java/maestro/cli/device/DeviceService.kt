package maestro.cli.device

import com.github.michaelbull.result.get
import dadb.Dadb
import io.grpc.ManagedChannelBuilder
import ios.idb.IdbIOSDevice
import maestro.MaestroTimer
import maestro.cli.CliError
import maestro.cli.device.ios.Simctl
import maestro.cli.util.EnvUtils
import java.io.File
import kotlin.concurrent.thread

object DeviceService {

    fun startDevice(device: Device.AvailableForLaunch): Device.Connected {
        when (device.platform) {
            Platform.IOS -> {
                Simctl.launchSimulator(device.modelId)
                Simctl.awaitLaunch(device.modelId)
                return Device.Connected(
                    instanceId = device.modelId,
                    description = device.description,
                    platform = device.platform,
                )
            }
            Platform.ANDROID -> {
                val androidHome = EnvUtils.androidHome()
                    ?: throw CliError("ANDROID_HOME environment variable is not set")

                val emulatorBinary = File(androidHome, "emulator/emulator")
                    .takeIf { it.exists() }
                    ?: File(androidHome, "tools/emulator")
                        .takeIf { it.exists() }
                    ?: throw CliError("Could not find emulator binary")

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
        }
    }

    fun prepareDevice(device: Device.Connected) {
        if (device.platform == Platform.IOS) {
            killIdbCompanion()
            startIdbCompanion(device)
        }
    }

    private fun startIdbCompanion(device: Device.Connected) {
        val idbProcess = ProcessBuilder("idb_companion", "--udid", device.instanceId)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()

        Runtime.getRuntime().addShutdownHook(thread(start = false) {
            idbProcess.destroy()
        })

        val iosDevice = IdbIOSDevice(
            ManagedChannelBuilder.forAddress("localhost", 10882)
                .usePlaintext()
                .build()
        )
        try {
            // Try to connect to the device repeatedly
            MaestroTimer.withTimeout(10000) {
                try {
                    // The idea is that view hierarchy is empty while device is still booting
                    iosDevice
                        .contentDescriptor()
                        .get()
                        ?.takeIf { nodes -> nodes.any { it.frame?.width != 0F } }
                } catch (ignored: Exception) {
                    // Ignore
                    null
                }
            } ?: error("idb_companion did not start in time")
        } finally {
            iosDevice.close()
        }
    }

    private fun killIdbCompanion() {
        ProcessBuilder("killall", "idb_companion")
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()
            .waitFor()
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
        return listAndroidDevices() + listIOSDevices()
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
            ProcessBuilder("emulator", "-list-avds")
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
            Simctl.list()
        } catch (ignored: Exception) {
            return emptyList()
        }

        val runtimes = simctlList
            .runtimes

        return runtimes
            .flatMap { runtime ->
                runtime.supportedDeviceTypes
                    .flatMap { supportedDeviceType ->
                        simctlList.devices
                            .values
                            .flatten()
                            .filter {
                                it.isAvailable &&
                                    it.deviceTypeIdentifier == supportedDeviceType.identifier
                            }
                    }
                    .map { device ->
                        val description = "${device.name} - ${runtime.name} - ${device.udid}"

                        if (device.state == "Booted") {
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
            }
    }

}
