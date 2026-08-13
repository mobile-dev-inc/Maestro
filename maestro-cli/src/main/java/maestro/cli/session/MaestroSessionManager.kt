/*
 *
 *  Copyright (c) 2022 mobile.dev inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 */

package maestro.cli.session

import maestro.android.AndroidDeviceConnection
import ios.LocalIOSDevice
import ios.devicectl.DeviceControlIOSDevice
import device.SimctlIOSDevice
import ios.xctest.XCTestIOSDevice
import maestro.Maestro
import maestro.device.Device
import maestro.cli.device.PickDeviceInteractor
import maestro.cli.driver.DriverBuilder
import maestro.cli.driver.RealIOSDeviceDriver
import maestro.cli.util.PrintUtils
import maestro.device.Platform
import maestro.utils.CliInsights
import maestro.cli.report.TestDebugReporter
import maestro.cli.util.ScreenReporter
import maestro.drivers.AndroidDriver
import maestro.drivers.IOSDriver
import maestro.orchestra.devicecore.DeviceCoreDriver
import maestro.orchestra.devicecore.RealDeviceCoreDriver
import maestro.orchestra.WorkspaceConfig.PlatformConfiguration
import maestro.orchestra.workspace.WorkspaceExecutionPlanner
import maestro.utils.TempFileHandler
import org.slf4j.LoggerFactory
import util.IOSDeviceType
import util.XCRunnerCLIUtils
import xcuitest.XCTestClient
import xcuitest.XCTestDriverClient
import xcuitest.installer.Context
import xcuitest.installer.LocalXCTestInstaller
import xcuitest.installer.LocalXCTestInstaller.*
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.pathString

object MaestroSessionManager {
    private const val defaultHost = "localhost"
    private const val defaultXctestHost = "127.0.0.1"
    private const val defaultXcTestPort = 22087

    private val executor = Executors.newScheduledThreadPool(1)
    private val logger = LoggerFactory.getLogger(MaestroSessionManager::class.java)


    fun <T> newSession(
        host: String?,
        port: Int?,
        driverHostPort: Int?,
        deviceId: String?,
        teamId: String? = null,
        platform: String? = null,
        isStudio: Boolean = false,
        isHeadless: Boolean = false,
        screenSize: String? = null,
        reinstallDriver: Boolean = true,
        deviceIndex: Int? = null,
        executionPlan: WorkspaceExecutionPlanner.ExecutionPlan? = null,
        block: (MaestroSession) -> T,
    ): T {
        val selectedDevice = selectDevice(
            host = host,
            port = port,
            driverHostPort = driverHostPort,
            deviceId = deviceId,
            teamId = teamId,
            platform = if(!platform.isNullOrEmpty()) Platform.fromString(platform) else null,
            deviceIndex = deviceIndex,
        )
        val sessionId = UUID.randomUUID().toString()
        val effectiveDeviceId = selectedDevice.device?.instanceId
            ?: selectedDevice.deviceId
            ?: sessionId // fallback: use session UUID as unique device key when no device ID is available

        val heartbeatFuture = executor.scheduleAtFixedRate(
            {
                try {
                    SessionStore.default.heartbeat(sessionId, selectedDevice.platform, effectiveDeviceId)
                } catch (e: Exception) {
                    logger.error("Failed to record heartbeat", e)
                }
            },
            0L,
            5L,
            TimeUnit.SECONDS
        )

        val session = createMaestro(
            selectedDevice = selectedDevice,
            connectToExistingSession = if (isStudio) {
                false
            } else {
                SessionStore.default.hasActiveSessionForDevice(
                    sessionId,
                    selectedDevice.platform,
                    effectiveDeviceId
                )
            },
            isStudio = isStudio,
            isHeadless = isHeadless,
            screenSize = screenSize,
            driverHostPort = driverHostPort,
            reinstallDriver = reinstallDriver,
            platformConfiguration = executionPlan?.workspaceConfig?.platform
        )
        Runtime.getRuntime().addShutdownHook(thread(start = false) {
            heartbeatFuture.cancel(true)
            SessionStore.default.delete(sessionId, selectedDevice.platform, effectiveDeviceId)
            runCatching { ScreenReporter.reportMaxDepth() }
            if (SessionStore.default.shouldCloseSession(selectedDevice.platform, effectiveDeviceId)) {
                session.close()
            }
        })

        return block(session)
    }

    /**
     * The device-core `maestro test` provisioning path. Resolves the target through the SAME
     * [selectDevice] used by [newSession] (so the right booted emulator/simulator is picked), but
     * NEVER calls [createMaestro] — no legacy [maestro.Maestro] is constructed here. Hands a
     * connected-lifecycle [DeviceCoreDriver] and the resolved [Platform] to [block] and closes the
     * driver in a `finally`.
     *
     * Callers `connect()` the driver inside [block] (the resolved [Platform] and the resolved device
     * serial are what they name the [maestro.orchestra.devicecore.DeviceCoreTarget] with).
     * Provisioning is split into [provisionDeviceCore] so the Maestro-less core is unit-testable
     * without a live device.
     */
    fun <T> newDeviceCoreSession(
        host: String?,
        port: Int?,
        driverHostPort: Int?,
        deviceId: String?,
        platform: Platform?,
        block: (driver: DeviceCoreDriver, platform: Platform, serial: String?) -> T,
    ): T {
        val selectedDevice = selectDevice(
            host = host,
            port = port,
            driverHostPort = driverHostPort,
            deviceId = deviceId,
            platform = platform,
        )
        val resolvedDeviceId = selectedDevice.device?.instanceId ?: selectedDevice.deviceId
        return provisionDeviceCore(
            platform = selectedDevice.platform,
            deviceId = resolvedDeviceId,
            block = block,
        )
    }

    /**
     * The Maestro-less provisioning core: build a [DeviceCoreDriver] via [driverFactory], run
     * [block], and close the driver. Constructs no [maestro.Maestro]. `internal` so
     * [maestro.cli.session.DeviceCoreSessionTest] can assert that directly.
     *
     * [deviceId] is the resolved target serial — for Android the adb serial (e.g. `emulator-5554`,
     * `SelectedDevice.device.instanceId`, which is `AdbDeviceConnection.serial`), for iOS the
     * simulator udid. It's handed to [block] so the caller can thread it into the
     * [maestro.orchestra.devicecore.DeviceCoreTarget], where device-core's `AdbSerialResolver`
     * matches it against `adb devices` to disambiguate when more than one device is attached.
     */
    internal fun <T> provisionDeviceCore(
        platform: Platform,
        deviceId: String?,
        driverFactory: () -> DeviceCoreDriver = { RealDeviceCoreDriver() },
        block: (driver: DeviceCoreDriver, platform: Platform, serial: String?) -> T,
    ): T {
        val driver = driverFactory()
        return try {
            block(driver, platform, deviceId)
        } finally {
            driver.close()
        }
    }

    private fun selectDevice(
        host: String?,
        port: Int?,
        driverHostPort: Int?,
        deviceId: String?,
        platform: Platform? = null,
        teamId: String? = null,
        deviceIndex: Int? = null,
    ): SelectedDevice {

        if (deviceId == "chromium" || platform == Platform.WEB) {
            return SelectedDevice(
                platform = Platform.WEB,
                deviceType = Device.DeviceType.BROWSER
            )
        }

        if (host == null) {
            val device = PickDeviceInteractor.pickDevice(deviceId, driverHostPort, platform, deviceIndex)

            if (device.deviceType == Device.DeviceType.REAL && device.platform == Platform.IOS) {
                PrintUtils.message("Detected connected iPhone with ${device.instanceId}!")
                val driverBuilder = DriverBuilder()
                RealIOSDeviceDriver(
                    destination = "platform=iOS,id=${device.instanceId}",
                    teamId = teamId,
                    driverBuilder = driverBuilder
                ).validateAndUpdateDriver()
            }
            return SelectedDevice(
                platform = device.platform,
                device = device,
                deviceType = device.deviceType
            )
        }

        if (isAndroid(host, port)) {
            val deviceType = when {
                deviceId?.startsWith("emulator") == true -> Device.DeviceType.EMULATOR
                else -> Device.DeviceType.REAL
            }
            return SelectedDevice(
                platform = Platform.ANDROID,
                host = host,
                port = port,
                deviceId = deviceId,
                deviceType = deviceType
            )
        }

        return SelectedDevice(
            platform = Platform.IOS,
            host = null,
            port = null,
            deviceId = deviceId,
            deviceType = Device.DeviceType.SIMULATOR
        )
    }

    private fun createMaestro(
        selectedDevice: SelectedDevice,
        connectToExistingSession: Boolean,
        isStudio: Boolean,
        isHeadless: Boolean,
        screenSize: String?,
        reinstallDriver: Boolean,
        driverHostPort: Int?,
        platformConfiguration: PlatformConfiguration? = null,
    ): MaestroSession {
        // W1 transitional scaffold: removed in W1.6 when Orchestra drops the Maestro param.
        // Provisioned alongside the legacy Maestro (not switched in for it) so Orchestra can be
        // handed both without a runtime driver-selection switch. Left unconnected here — nothing
        // on this path calls a driver.* verb yet (that's W1.3-W1.5), and device-core has no web
        // provider, so eagerly connecting would throw for the web platform this path still serves.
        val driver = RealDeviceCoreDriver()
        return when {
            selectedDevice.device != null -> MaestroSession(
                maestro = when (selectedDevice.device.platform) {
                    Platform.ANDROID -> createAndroid(
                        selectedDevice.device.instanceId,
                        !connectToExistingSession,
                        driverHostPort,
                        reinstallDriver,
                    )

                    Platform.IOS -> createIOS(
                        selectedDevice.device.instanceId,
                        !connectToExistingSession,
                        driverHostPort,
                        reinstallDriver,
                        deviceType = selectedDevice.device.deviceType,
                        platformConfiguration = platformConfiguration
                    )

                    Platform.WEB -> pickWebDevice(isStudio, isHeadless, screenSize)
                },
                device = selectedDevice.device,
                driver = driver,
                platform = selectedDevice.device.platform,
            )

            selectedDevice.platform == Platform.ANDROID -> MaestroSession(
                maestro = pickAndroidDevice(
                    selectedDevice.host,
                    selectedDevice.port,
                    driverHostPort,
                    !connectToExistingSession,
                    reinstallDriver,
                    selectedDevice.deviceId,
                ),
                device = null,
                driver = driver,
                platform = selectedDevice.platform,
            )

            selectedDevice.platform == Platform.IOS -> MaestroSession(
                maestro = pickIOSDevice(
                    deviceId = selectedDevice.deviceId,
                    openDriver = !connectToExistingSession,
                    driverHostPort = driverHostPort ?: defaultXcTestPort,
                    reinstallDriver = reinstallDriver,
                    platformConfiguration = platformConfiguration,
                ),
                device = null,
                driver = driver,
                platform = selectedDevice.platform,
            )

            selectedDevice.platform == Platform.WEB -> MaestroSession(
                maestro = pickWebDevice(isStudio, isHeadless, screenSize),
                device = null,
                driver = driver,
                platform = selectedDevice.platform,
            )

            else -> error("Unable to create Maestro session")
        }
    }

    private fun isAndroid(host: String?, port: Int?): Boolean {
        return try {
            val connection = if (port != null) {
                AndroidDeviceConnection.open(host ?: defaultHost, port)
            } else {
                AndroidDeviceConnection.discover(host ?: defaultHost)
                    ?: createAdbServerConnection()
                    ?: error("No android devices found.")
            }

            connection.close()

            true
        } catch (_: Exception) {
            false
        }
    }

    private fun pickAndroidDevice(
        host: String?,
        port: Int?,
        driverHostPort: Int?,
        openDriver: Boolean,
        reinstallDriver: Boolean,
        deviceId: String? = null,
    ): Maestro {
        val resolvedDriverHostPort = driverHostPort ?: AndroidDeviceConnection.DEFAULT_DRIVER_HOST_PORT
        val connection = if (port != null) {
            AndroidDeviceConnection.open(host ?: defaultHost, port, resolvedDriverHostPort)
        } else if (deviceId != null) {
            AndroidDeviceConnection.byId(deviceId, host ?: defaultHost, resolvedDriverHostPort)
                ?: error("No Android device found with id '$deviceId' on host '${host ?: defaultHost}'")
        } else {
            AndroidDeviceConnection.discover(host ?: defaultHost, resolvedDriverHostPort)
                ?: createAdbServerConnection(resolvedDriverHostPort)
                ?: error("No android devices found.")
        }

        return Maestro.android(
            driver = AndroidDriver(connection, "", reinstallDriver),
            openDriver = openDriver,
        )
    }

    private fun createAdbServerConnection(
        driverHostPort: Int = AndroidDeviceConnection.DEFAULT_DRIVER_HOST_PORT,
    ): AndroidDeviceConnection? {
        return AndroidDeviceConnection.adbServer(adbServerPort = 5038, driverHostPort = driverHostPort)
    }

    private fun pickIOSDevice(
        deviceId: String?,
        openDriver: Boolean,
        driverHostPort: Int,
        reinstallDriver: Boolean,
        platformConfiguration: PlatformConfiguration?,
    ): Maestro {
        val device = PickDeviceInteractor.pickDevice(deviceId, driverHostPort)
        return createIOS(
            device.instanceId,
            openDriver,
            driverHostPort,
            reinstallDriver,
            deviceType = device.deviceType,
            platformConfiguration = platformConfiguration
        )
    }

    private fun createAndroid(
        instanceId: String,
        openDriver: Boolean,
        driverHostPort: Int?,
        reinstallDriver: Boolean,
    ): Maestro {
        val resolvedDriverHostPort = driverHostPort ?: AndroidDeviceConnection.DEFAULT_DRIVER_HOST_PORT
        val connection = AndroidDeviceConnection.byId(instanceId, driverHostPort = resolvedDriverHostPort)
            ?: AndroidDeviceConnection.discover("localhost", resolvedDriverHostPort)
            ?: error("Unable to find device with id $instanceId")
        val driver = AndroidDriver(
            connection = connection,
            emulatorName = instanceId,
            reinstallDriver = reinstallDriver,
        )

        return Maestro.android(
            driver = driver,
            openDriver = openDriver,
        )
    }

    private fun createIOS(
        deviceId: String,
        openDriver: Boolean,
        driverHostPort: Int?,
        reinstallDriver: Boolean,
        platformConfiguration: PlatformConfiguration?,
        deviceType: Device.DeviceType,
    ): Maestro {

        val iOSDeviceType = when (deviceType) {
            Device.DeviceType.REAL -> IOSDeviceType.REAL
            Device.DeviceType.SIMULATOR -> IOSDeviceType.SIMULATOR
            else -> {
                throw UnsupportedOperationException("Unsupported device type $deviceType for iOS platform")
            }
        }
        val iOSDriverConfig = when (deviceType) {
            Device.DeviceType.REAL -> {
                val maestroDirectory = Paths.get(System.getProperty("user.home"), ".maestro")
                val driverPath = maestroDirectory.resolve("maestro-iphoneos-driver-build").resolve("driver-iphoneos")
                    .resolve("Build").resolve("Products")
                IOSDriverConfig(
                    prebuiltRunner = false,
                    sourceDirectory = driverPath.pathString,
                    context = Context.CLI,
                    snapshotKeyHonorModalViews = platformConfiguration?.ios?.snapshotKeyHonorModalViews
                )
            }
            Device.DeviceType.SIMULATOR -> {
                IOSDriverConfig(
                    prebuiltRunner = false,
                    sourceDirectory =  "driver-iPhoneSimulator",
                    context = Context.CLI,
                    snapshotKeyHonorModalViews = platformConfiguration?.ios?.snapshotKeyHonorModalViews
                )
            }
             else -> throw UnsupportedOperationException("Unsupported device type $deviceType for iOS platform")
        }

        val tempFileHandler = TempFileHandler()
        val deviceController = when (deviceType) {
            Device.DeviceType.REAL -> {
                val device = util.LocalIOSDevice().listDeviceViaDeviceCtl(deviceId)
                val deviceCtlDevice = DeviceControlIOSDevice(deviceId = device.identifier)
                deviceCtlDevice
            }
            Device.DeviceType.SIMULATOR -> {
                val simctlIOSDevice = SimctlIOSDevice(
                    deviceId = deviceId,
                    tempFileHandler = tempFileHandler
                )
                simctlIOSDevice
            }
            else -> throw UnsupportedOperationException("Unsupported device type $deviceType for iOS platform")
        }

        val xcTestInstaller = LocalXCTestInstaller(
            deviceId = deviceId,
            host = defaultXctestHost,
            defaultPort = driverHostPort ?: defaultXcTestPort,
            reinstallDriver = reinstallDriver,
            deviceType = iOSDeviceType,
            iOSDriverConfig = iOSDriverConfig,
            deviceController = deviceController,
            tempFileHandler = tempFileHandler,
            logsDir = TestDebugReporter.getDebugOutputPath().toFile(),
        )

        val xcTestDriverClient = XCTestDriverClient(
            installer = xcTestInstaller,
            client = XCTestClient(defaultXctestHost, driverHostPort ?: defaultXcTestPort),
            reinstallDriver = reinstallDriver,
        )

        val xcRunnerCLIUtils = XCRunnerCLIUtils(tempFileHandler = tempFileHandler)
        val xcTestDevice = XCTestIOSDevice(
            deviceId = deviceId,
            client = xcTestDriverClient,
            getInstalledApps = { xcRunnerCLIUtils.listApps(deviceId) },
        )

        val iosDriver = IOSDriver(
            LocalIOSDevice(
                deviceId = deviceId,
                xcTestDevice = xcTestDevice,
                deviceController = deviceController,
                insights = CliInsights
            ),
            insights = CliInsights,
            xctestLogsDir = TestDebugReporter.getDebugOutputPath().toFile(),
        )

        return Maestro.ios(
            driver = iosDriver,
            openDriver = openDriver || xcTestDevice.isShutdown(),
        )
    }

    private fun pickWebDevice(isStudio: Boolean, isHeadless: Boolean, screenSize: String?): Maestro {
        return Maestro.web(isStudio, isHeadless, screenSize)
    }

    private data class SelectedDevice(
        val platform: Platform,
        val device: Device.Connected? = null,
        val host: String? = null,
        val port: Int? = null,
        val deviceId: String? = null,
        val deviceType: Device.DeviceType,
    )

    data class MaestroSession(
        val maestro: Maestro,
        val device: Device? = null,
        // W1 transitional scaffold: removed in W1.6 when Orchestra drops the Maestro param.
        val driver: DeviceCoreDriver,
        val platform: Platform,
    ) {

        fun close() {
            maestro.close()
            driver.close()
        }
    }
}
