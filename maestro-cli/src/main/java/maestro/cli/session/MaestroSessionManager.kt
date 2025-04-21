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

import dadb.Dadb
import dadb.adbserver.AdbServer
import ios.LocalIOSDevice
import ios.devicectl.DeviceControlIOSDevice
import ios.simctl.SimctlIOSDevice
import ios.xctest.XCTestIOSDevice
import maestro.Maestro
import maestro.device.Device
import maestro.cli.device.PickDeviceInteractor
import maestro.device.Platform
import maestro.utils.CliInsights
import maestro.cli.util.ScreenReporter
import maestro.drivers.AndroidDriver
import maestro.drivers.IOSDriver
import org.slf4j.LoggerFactory
import util.IOSDeviceType
import util.XCRunnerCLIUtils
import xcuitest.XCTestClient
import xcuitest.XCTestDriverClient
import xcuitest.installer.LocalXCTestInstaller
import xcuitest.installer.LocalXCTestInstaller.*
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

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
        platform: String? = null,
        isStudio: Boolean = false,
        isHeadless: Boolean = isStudio,
        reinstallDriver: Boolean = true,
        block: (MaestroSession) -> T,
    ): T {
        val selectedDevice = selectDevice(
            host = host,
            port = port,
            driverHostPort = driverHostPort,
            deviceId = deviceId,
            platform = Platform.fromString(platform)
        )
        val sessionId = UUID.randomUUID().toString()

        val heartbeatFuture = executor.scheduleAtFixedRate(
            {
                try {
                    Thread.sleep(1000) // Add a 1-second delay here for fixing race condition
                    SessionStore.heartbeat(sessionId, selectedDevice.platform)
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
                SessionStore.hasActiveSessions(
                    sessionId,
                    selectedDevice.platform
                )
            },
            isStudio = isStudio,
            isHeadless = isHeadless,
            driverHostPort = driverHostPort,
            reinstallDriver = reinstallDriver,
        )
        Runtime.getRuntime().addShutdownHook(thread(start = false) {
            heartbeatFuture.cancel(true)
            SessionStore.delete(sessionId, selectedDevice.platform)
            runCatching { ScreenReporter.reportMaxDepth() }
            if (SessionStore.activeSessions().isEmpty()) {
                session.close()
            }
        })

        return block(session)
    }

    private fun selectDevice(
        host: String?,
        port: Int?,
        driverHostPort: Int?,
        deviceId: String?,
        platform: Platform? = null,
    ): SelectedDevice {

        if (deviceId == "chromium" || platform == Platform.WEB) {
            return SelectedDevice(
                platform = Platform.WEB,
                deviceType = Device.DeviceType.BROWSER
            )
        }

        if (host == null) {
            val device = PickDeviceInteractor.pickDevice(deviceId, driverHostPort, platform)

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
        reinstallDriver: Boolean,
        driverHostPort: Int?,
    ): MaestroSession {
        return when {
            selectedDevice.device != null -> MaestroSession(
                maestro = when (selectedDevice.device.platform) {
                    Platform.ANDROID -> createAndroid(
                        selectedDevice.device.instanceId,
                        !connectToExistingSession,
                        driverHostPort,
                    )

                    Platform.IOS -> createIOS(
                        selectedDevice.device.instanceId,
                        !connectToExistingSession,
                        driverHostPort,
                        reinstallDriver,
                        selectedDevice.device.deviceType
                    )

                    Platform.WEB -> pickWebDevice(isStudio, isHeadless)
                },
                device = selectedDevice.device,
            )

            selectedDevice.platform == Platform.ANDROID -> MaestroSession(
                maestro = pickAndroidDevice(
                    selectedDevice.host,
                    selectedDevice.port,
                    driverHostPort,
                    !connectToExistingSession,
                ),
                device = null,
            )

            selectedDevice.platform == Platform.IOS -> MaestroSession(
                maestro = pickIOSDevice(
                    deviceId = selectedDevice.deviceId,
                    openDriver = !connectToExistingSession,
                    driverHostPort = driverHostPort ?: defaultXcTestPort,
                    reinstallDriver = reinstallDriver,
                ),
                device = null,
            )

            selectedDevice.platform == Platform.WEB -> MaestroSession(
                maestro = pickWebDevice(isStudio, isHeadless),
                device = null
            )

            else -> error("Unable to create Maestro session")
        }
    }

    private fun isAndroid(host: String?, port: Int?): Boolean {
        return try {
            val dadb = if (port != null) {
                Dadb.create(host ?: defaultHost, port)
            } else {
                Dadb.discover(host ?: defaultHost)
                    ?: createAdbServerDadb()
                    ?: error("No android devices found.")
            }

            dadb.close()

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
    ): Maestro {
        val dadb = if (port != null) {
            Dadb.create(host ?: defaultHost, port)
        } else {
            Dadb.discover(host ?: defaultHost)
                ?: createAdbServerDadb()
                ?: error("No android devices found.")
        }

        return Maestro.android(
            driver = AndroidDriver(dadb, driverHostPort),
            openDriver = openDriver,
        )
    }

    private fun createAdbServerDadb(): Dadb? {
        return try {
            AdbServer.createDadb(adbServerPort = 5038)
        } catch (ignored: Exception) {
            null
        }
    }

    private fun pickIOSDevice(
        deviceId: String?,
        openDriver: Boolean,
        driverHostPort: Int,
        reinstallDriver: Boolean,
    ): Maestro {
        val device = PickDeviceInteractor.pickDevice(deviceId, driverHostPort)
        return createIOS(
            device.instanceId,
            openDriver,
            driverHostPort,
            reinstallDriver,
            device.deviceType
        )
    }

    private fun createAndroid(
        instanceId: String,
        openDriver: Boolean,
        driverHostPort: Int?,
    ): Maestro {
        val driver = AndroidDriver(
            dadb = Dadb
                .list()
                .find { it.toString() == instanceId }
                ?: Dadb.discover()
                ?: error("Unable to find device with id $instanceId"),
            hostPort = driverHostPort,
            emulatorName = instanceId,
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
                IOSDriverConfig(
                    prebuiltRunner = false,
                    sourceDirectory = "driver-iphoneos"
                )
            }
            Device.DeviceType.SIMULATOR -> {
                IOSDriverConfig(
                    prebuiltRunner = true,
                    sourceDirectory =  "driver-iPhoneSimulator"
                )
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
        )

        val xcTestDriverClient = XCTestDriverClient(
            installer = xcTestInstaller,
            client = XCTestClient(defaultXctestHost, driverHostPort ?: defaultXcTestPort),
            reinstallDriver = reinstallDriver,
        )

        val xcTestDevice = XCTestIOSDevice(
            deviceId = deviceId,
            client = xcTestDriverClient,
            getInstalledApps = { XCRunnerCLIUtils.listApps(deviceId) },
        )

        val deviceController = when (deviceType) {
            Device.DeviceType.REAL -> {
                val device = util.LocalIOSDevice.listDeviceViaDeviceCtl(deviceId)
                val deviceCtlDevice = DeviceControlIOSDevice(deviceId = device.identifier)
                deviceCtlDevice
            }
            Device.DeviceType.SIMULATOR -> {
                val simctlIOSDevice = SimctlIOSDevice(
                    deviceId = deviceId,
                )
                simctlIOSDevice
            }
            else -> throw UnsupportedOperationException("Unsupported device type $deviceType for iOS platform")
        }

        val iosDriver = IOSDriver(
            LocalIOSDevice(
                deviceId = deviceId,
                xcTestDevice = xcTestDevice,
                deviceController = deviceController,
                insights = CliInsights
            ),
            insights = CliInsights
        )

        return Maestro.ios(
            driver = iosDriver,
            openDriver = openDriver || xcTestDevice.isShutdown(),
        )
    }

    private fun pickWebDevice(isStudio: Boolean, isHeadless: Boolean): Maestro {
        return Maestro.web(isStudio, isHeadless)
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
    ) {

        fun close() {
            maestro.close()
        }
    }
}
