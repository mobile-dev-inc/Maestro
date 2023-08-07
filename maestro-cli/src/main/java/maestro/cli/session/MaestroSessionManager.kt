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
import io.grpc.ManagedChannelBuilder
import ios.LocalIOSDevice
import ios.simctl.SimctlIOSDevice
import ios.xctest.XCTestIOSDevice
import maestro.Maestro
import maestro.cli.device.Device
import maestro.cli.device.PickDeviceInteractor
import maestro.cli.device.Platform
import maestro.cli.util.ScreenReporter
import maestro.debuglog.IOSDriverLogger
import maestro.drivers.AndroidDriver
import maestro.drivers.IOSDriver
import org.slf4j.LoggerFactory
import sun.misc.Signal
import sun.misc.SignalHandler
import util.XCRunnerCLIUtils
import xcuitest.XCTestClient
import xcuitest.XCTestDriverClient
import xcuitest.installer.LocalXCTestInstaller
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

object MaestroSessionManager {
    private const val defaultHost = "localhost"
    private const val defaultIdbPort = 10882
    private const val defaultXcTestPort = 22087

    private val executor = Executors.newScheduledThreadPool(1)
    private val logger = LoggerFactory.getLogger(MaestroSessionManager::class.java)

    fun <T> newSession(
        host: String?,
        port: Int?,
        deviceId: String?,
        isStudio: Boolean = false,
        block: (MaestroSession) -> T,
    ): T {
        val selectedDevice = selectDevice(host, port, deviceId)
        val sessionId = UUID.randomUUID().toString()

        val heartbeatFuture = executor.scheduleAtFixedRate(
            {
                try {
                    SessionStore.heartbeat(sessionId, selectedDevice.platform)
                } catch (e: Exception) {
                    logger.error("Failed to record heartbeat", e)
                }
            },
            0L,
            5L,
            TimeUnit.SECONDS
        )

        val session = SessionStore.withExclusiveLock {
            createMaestro(
                selectedDevice = selectedDevice,
                connectToExistingSession = SessionStore.hasActiveSessions(sessionId, selectedDevice.platform),
                isStudio = isStudio
            )
        }
        Runtime.getRuntime().addShutdownHook(thread(start = false) {
            SessionStore.withExclusiveLock {
                heartbeatFuture.cancel(true)
                SessionStore.delete(sessionId, selectedDevice.platform)
                runCatching { ScreenReporter.reportMaxDepth() }

                if (SessionStore.activeSessions().isEmpty()) {
                    session.close()
                }
            }
        })
        Signal.handle(CustomSignalHandler.suspendSignal, CustomSignalHandler())

        return block(session)
    }

    private fun selectDevice(
        host: String?,
        port: Int?,
        deviceId: String?,
    ): SelectedDevice {
        if (deviceId == "chromium") {
            return SelectedDevice(
                platform = Platform.WEB
            )
        }

        if (host == null) {
            val device = PickDeviceInteractor.pickDevice(deviceId)

            return SelectedDevice(
                platform = device.platform,
                device = device,
            )
        }

        if (isAndroid(host, port)) {
            return SelectedDevice(
                platform = Platform.ANDROID,
                host = host,
                port = port,
                deviceId = deviceId,
            )
        }

        return SelectedDevice(
            platform = Platform.IOS,
            host = null,
            port = null,
            deviceId = deviceId,
        )
    }

    private fun createMaestro(
        selectedDevice: SelectedDevice,
        connectToExistingSession: Boolean,
        isStudio: Boolean,
    ): MaestroSession {
        return when {
            selectedDevice.device != null -> MaestroSession(
                maestro = when (selectedDevice.device.platform) {
                    Platform.ANDROID -> createAndroid(
                        selectedDevice.device.instanceId,
                        !connectToExistingSession
                    )

                    Platform.IOS -> createIOS(
                        selectedDevice.device.instanceId,
                        !connectToExistingSession
                    )

                    Platform.WEB -> pickWebDevice(isStudio)
                },
                device = selectedDevice.device,
            )

            selectedDevice.platform == Platform.ANDROID -> MaestroSession(
                maestro = pickAndroidDevice(
                    selectedDevice.host,
                    selectedDevice.port,
                    !connectToExistingSession,
                ),
                device = null,
            )

            selectedDevice.platform == Platform.IOS -> MaestroSession(
                maestro = pickIOSDevice(
                    selectedDevice.deviceId,
                    !connectToExistingSession,
                ),
                device = null,
            )

            selectedDevice.platform == Platform.WEB -> MaestroSession(
                maestro = pickWebDevice(isStudio),
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

    private fun isIOS(host: String?, port: Int?): Boolean {
        return try {
            val channel = ManagedChannelBuilder.forAddress(host ?: defaultHost, port ?: defaultIdbPort)
                .usePlaintext()
                .build()

            channel.shutdownNow()

            true
        } catch (_: Exception) {
            false
        }
    }

    private fun pickAndroidDevice(
        host: String?,
        port: Int?,
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
            driver = AndroidDriver(dadb),
            openDriver = openDriver,
        )
    }

    private fun createAdbServerDadb(): Dadb? {
        return try {
            AdbServer.createDadb()
        } catch (ignored: Exception) {
            null
        }
    }

    private fun pickIOSDevice(
        deviceId: String?,
        openDriver: Boolean,
    ): Maestro {
        val device = PickDeviceInteractor.pickDevice(deviceId)
        return createIOS(device.instanceId, openDriver)
    }

    private fun createAndroid(instanceId: String, openDriver: Boolean): Maestro {
        val driver = AndroidDriver(
            dadb = Dadb
                .list()
                .find { it.toString() == instanceId }
                ?: Dadb.discover()
                ?: error("Unable to find device with id $instanceId"),
        )

        return Maestro.android(
            driver = driver,
            openDriver = openDriver,
        )
    }

    private fun createIOS(
        deviceId: String,
        openDriver: Boolean,
    ): Maestro {

        val xcTestInstaller = LocalXCTestInstaller(
            logger = IOSDriverLogger(LocalXCTestInstaller::class.java),
            deviceId = deviceId,
            host = defaultHost,
            defaultPort = defaultXcTestPort
        )

        val xcTestDriverClient = XCTestDriverClient(
            installer = xcTestInstaller,
            logger = IOSDriverLogger(XCTestDriverClient::class.java),
            client = XCTestClient(defaultHost, defaultXcTestPort)
        )

        val xcTestDevice = XCTestIOSDevice(
            deviceId = deviceId,
            client = xcTestDriverClient,
            getInstalledApps = { XCRunnerCLIUtils.listApps(deviceId) },
            logger = IOSDriverLogger(XCTestIOSDevice::class.java),
        )

        val simctlIOSDevice = SimctlIOSDevice(
            deviceId = deviceId,
        )

        val iosDriver = IOSDriver(
            LocalIOSDevice(
                deviceId = deviceId,
                xcTestDevice = xcTestDevice,
                simctlIOSDevice = simctlIOSDevice,
            )
        )

        return Maestro.ios(
            driver = iosDriver,
            openDriver = openDriver || xcTestDevice.isShutdown(),
        )
    }

    private fun pickWebDevice(isStudio: Boolean): Maestro {
        return Maestro.web(isStudio)
    }

    private data class SelectedDevice(
        val platform: Platform,
        val device: Device.Connected? = null,
        val host: String? = null,
        val port: Int? = null,
        val deviceId: String? = null,
    )

    data class MaestroSession(
        val maestro: Maestro,
        val device: Device? = null,
    ) {

        fun close() {
            maestro.close()
        }
    }

    private class CustomSignalHandler() : SignalHandler {
        override fun handle(signal: Signal) {
            when (signal) {
                suspendSignal -> exitProcess(0)
            }
        }

        companion object {
            val suspendSignal = Signal("TSTP")
        }
    }
}
