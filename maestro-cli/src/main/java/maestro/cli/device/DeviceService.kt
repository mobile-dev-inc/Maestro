package maestro.cli.device

import com.github.michaelbull.result.get
import dadb.Dadb
import io.grpc.ManagedChannelBuilder
import ios.idb.IdbIOSDevice
import maestro.cli.CliError
import maestro.cli.debuglog.DebugLogStore
import ios.xcrun.Simctl
import ios.xcrun.Simctl.SimctlError
import ios.xcrun.SimctlList
import maestro.cli.util.EnvUtils
import maestro.utils.MaestroTimer
import java.io.File
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

object DeviceService {

    private val NULL_FILE = File(
        if (System.getProperty("os.name")
                .startsWith("Windows")
        ) "NUL" else "/dev/null"
    )

    private val logger = DebugLogStore.loggerFor(DeviceService::class.java)

    fun startDevice(device: Device.AvailableForLaunch): Device.Connected {
        when (device.platform) {
            Platform.IOS -> {
                try {
                    Simctl.launchSimulator(device.modelId)
                    Simctl.awaitLaunch(device.modelId)
                } catch (e: SimctlError) {
                    throw CliError(e.message)
                }

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
            startIdbCompanion(device)
        }
    }

    private fun startIdbCompanion(device: Device.Connected) {
        logger.info("startIDBCompanion on $device")

        val idbHost = "localhost"
        val idbPort = 10882

        val isIdbCompanionRunning =
            try { Socket(idbHost, idbPort).use { true } }
            catch(_: Exception) { false }

        if (isIdbCompanionRunning) {
            error("idb_companion is already running. Stop idb_companion and run maestro again")
        }

        val idbProcessBuilder = ProcessBuilder("idb_companion", "--udid", device.instanceId)
        DebugLogStore.logOutputOf(idbProcessBuilder)
        val idbProcess = idbProcessBuilder.start()

        Runtime.getRuntime().addShutdownHook(thread(start = false) {
            idbProcess.destroy()
        })

        val channel = ManagedChannelBuilder.forAddress(idbHost, idbPort)
            .usePlaintext()
            .build()

        IdbIOSDevice(channel).use { iosDevice ->
            logger.warning("Waiting for idb service to start..")
            MaestroTimer.retryUntilTrue(timeoutMs = 60000, delayMs = 100) {
                Socket(idbHost, idbPort).use { true }
            } || error("idb_companion did not start in time")

            // The first time a simulator boots up, it can
            // take 10's of seconds to complete.
            logger.warning("Waiting for Simulator to boot..")
            MaestroTimer.retryUntilTrue(timeoutMs = 120000, delayMs = 100) {
                val process = ProcessBuilder("xcrun", "simctl", "bootstatus", device.instanceId)
                    .start()
                process
                    .waitFor(1000, TimeUnit.MILLISECONDS)
                process.exitValue() == 0
            } || error("Simulator failed to boot")

            // Test if idb can get accessibility info elements with non-zero frame with
            logger.warning("Waiting for Accessibility info to become available..")
            MaestroTimer.retryUntilTrue(timeoutMs = 20000, delayMs = 100) {
                val nodes = iosDevice
                    .contentDescriptor()
                    .get()

                nodes?.any { it.frame?.width != 0F } == true
            } || error("idb_companion is not able to fetch accessibility info")

            logger.warning("Simulator ready")
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

}
