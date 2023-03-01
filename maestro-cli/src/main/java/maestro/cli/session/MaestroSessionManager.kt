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
import ios.idb.IdbIOSDevice
import ios.simctl.SimctlIOSDevice
import ios.xctest.XCTestIOSDevice
import maestro.Maestro
import maestro.cli.device.Device
import maestro.cli.device.PickDeviceInteractor
import maestro.cli.device.Platform
import maestro.cli.idb.IdbInstaller
import maestro.debuglog.IOSDriverLogger
import maestro.drivers.IOSDriver
import org.slf4j.LoggerFactory
import util.XCRunnerSimctl
import xcuitest.XCTestDriverClient
import xcuitest.installer.LocalXCTestInstaller
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

object MaestroSessionManager {
    private const val defaultHost = "localhost"
    private const val idbPort = 10882
    private const val xcTestPort = 22087

    private val executor = Executors.newScheduledThreadPool(1)
    private val logger = LoggerFactory.getLogger(MaestroSessionManager::class.java)

    fun <T> newSession(
        host: String?,
        port: Int?,
        deviceId: String?,

        // needed for experimental web support
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

                if (SessionStore.activeSessions().isEmpty()) {
                    session.close()
                }
            }
        })

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

        if (isIOS(host, port)) {
            return SelectedDevice(
                platform = Platform.IOS,
                host = host,
                port = port,
                deviceId = deviceId,
            )
        }

        error("No devices found.")
    }

    private fun createMaestro(
        selectedDevice: SelectedDevice,
        connectToExistingSession: Boolean,
        isStudio: Boolean
    ): MaestroSession {
        return when {
            selectedDevice.device != null -> MaestroSession(
                maestro = when (selectedDevice.device.platform) {
                    Platform.ANDROID -> {
                        Maestro.android(
                            dadb = Dadb
                                .list()
                                .find { it.toString() == selectedDevice.device.instanceId }
                                ?: Dadb.discover()
                                ?: error("Unable to find device with id ${selectedDevice.device.instanceId}"),
                            openDriver = !connectToExistingSession,
                        )
                    }
                    Platform.IOS -> {
                        val channel = ManagedChannelBuilder.forAddress(defaultHost, idbPort)
                            .usePlaintext()
                            .build()

                        val xcTestInstaller = LocalXCTestInstaller(
                            logger = IOSDriverLogger(),
                            deviceId = selectedDevice.device.instanceId,
                        )
                        // TODO (as) remove this flag with a proper factory
                        val installNetworkInterceptor = isStudio
                        val xcTestDriverClient = XCTestDriverClient(defaultHost, xcTestPort, installNetworkInterceptor) {
                            if (SessionStore.activeSessions().isNotEmpty()) {
                                IdbInstaller.setup(selectedDevice.device)
                                return@XCTestDriverClient xcTestInstaller.setup()
                            }
                            return@XCTestDriverClient false
                        }

                        val xcTestDevice = XCTestIOSDevice(
                            deviceId = selectedDevice.device.instanceId,
                            client = xcTestDriverClient,
                            installer = xcTestInstaller,
                            getInstalledApps = { XCRunnerSimctl.listApps() },
                            logger = IOSDriverLogger(),
                        )

                        val simctlIOSDevice = SimctlIOSDevice(
                            deviceId = selectedDevice.device.instanceId,
                            xcTestDevice = xcTestDevice,
                        )

                        Maestro.ios(
                            driver = IOSDriver(
                                LocalIOSDevice(
                                    deviceId = selectedDevice.device.instanceId,
                                    idbIOSDevice = IdbIOSDevice(
                                        channel = channel,
                                        deviceId = selectedDevice.device.instanceId,
                                    ),
                                    xcTestDevice = xcTestDevice,
                                    simctlIOSDevice = simctlIOSDevice,
                                )
                            ),
                            openDriver = !connectToExistingSession || xcTestDevice.isShutdown(),
                        )
                    }
                    Platform.WEB -> createWeb(isStudio = isStudio)
                },
                device = selectedDevice.device,
            )
            selectedDevice.platform == Platform.ANDROID -> MaestroSession(
                maestro = createAndroid(
                    selectedDevice.host,
                    selectedDevice.port,
                    !connectToExistingSession,
                ),
                device = null,
            )
            selectedDevice.platform == Platform.IOS -> MaestroSession(
                maestro = createIOS(
                    selectedDevice.host,
                    selectedDevice.port,
                    selectedDevice.deviceId,
                    !connectToExistingSession,
                    isStudio
                ),
                device = null,
            )
            selectedDevice.platform == Platform.WEB -> MaestroSession(
                maestro = createWeb(isStudio),
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
            val channel = ManagedChannelBuilder.forAddress(host ?: defaultHost, port ?: idbPort)
                .usePlaintext()
                .build()

            channel.shutdownNow()

            true
        } catch (_: Exception) {
            false
        }
    }

    private fun createAndroid(
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
            dadb = dadb,
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

    private fun createIOS(
        host: String?,
        port: Int?,
        deviceId: String?,
        openDriver: Boolean,
        isStudio: Boolean,
    ): Maestro {
        val channel = ManagedChannelBuilder.forAddress(host ?: defaultHost, port ?: idbPort)
            .usePlaintext()
            .build()
        val device = PickDeviceInteractor.pickDevice(deviceId)
        val idbIOSDevice = IdbIOSDevice(channel, device.instanceId)
        val xcTestInstaller = LocalXCTestInstaller(
            logger = IOSDriverLogger(),
            deviceId = device.instanceId,
        )
        val xcTestDriverClient = XCTestDriverClient(defaultHost, xcTestPort, isStudio) {
            if (SessionStore.activeSessions().isNotEmpty()) {
                IdbInstaller.setup(device)
                return@XCTestDriverClient xcTestInstaller.setup()
            }
            return@XCTestDriverClient false
        }
        val xcTestDevice = XCTestIOSDevice(
            deviceId = device.instanceId,
            client = xcTestDriverClient,
            installer = xcTestInstaller,
            getInstalledApps = { XCRunnerSimctl.listApps() },
            logger = IOSDriverLogger(),
        )
        val simctlIOSDevice = SimctlIOSDevice(
            deviceId = device.instanceId,
            xcTestDevice = xcTestDevice,
        )

        val iosDriver = IOSDriver(
            LocalIOSDevice(
                deviceId = device.instanceId,
                idbIOSDevice = idbIOSDevice,
                xcTestDevice = XCTestIOSDevice(
                    deviceId = device.instanceId,
                    client = xcTestDriverClient,
                    installer = xcTestInstaller,
                    getInstalledApps = { XCRunnerSimctl.listApps() },
                    logger = IOSDriverLogger(),
                ),
                simctlIOSDevice = simctlIOSDevice,
            )
        )
        return Maestro.ios(
            driver = iosDriver,
            openDriver = openDriver || xcTestDevice.isShutdown(),
        )
    }

    private fun createWeb(isStudio: Boolean): Maestro {
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

}
