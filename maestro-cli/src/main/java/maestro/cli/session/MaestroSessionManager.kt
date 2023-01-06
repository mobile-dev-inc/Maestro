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
import ios.idb.IdbIOSDevice
import maestro.Maestro
import maestro.cli.device.Device
import maestro.cli.device.PickDeviceInteractor
import maestro.cli.device.Platform
import maestro.drivers.IOSDriver
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

object MaestroSessionManager {
    private const val defaultHost = "localhost"
    private const val idbPort = 10882

    private val executor = Executors.newScheduledThreadPool(1)

    fun <T> newSession(
        host: String?,
        port: Int?,
        deviceId: String?,
        block: (MaestroSession) -> T,
    ): T {
        val selectedDevice = selectDevice(host, port, deviceId)
        val sessionId = UUID.randomUUID().toString()

        val heartbeatFuture = executor.scheduleAtFixedRate(
            {
                SessionStore.heartbeat(sessionId, selectedDevice.platform)
            },
            0L,
            10L,
            TimeUnit.SECONDS
        )

        val session = SessionStore.withExclusiveLock {
            createMaestro(
                selectedDevice = selectedDevice,
                connectToExistingSession = SessionStore.hasActiveSessions(sessionId, selectedDevice.platform),
            )
        }

        Runtime.getRuntime().addShutdownHook(thread(start = false) {
            SessionStore.withExclusiveLock {
                SessionStore.delete(sessionId, selectedDevice.platform)

                if (!SessionStore.hasActiveSessions(sessionId, selectedDevice.platform)) {
                    session.close()
                }
            }
        })

        return try {
            block(session)
        } finally {
            heartbeatFuture.cancel(true)
        }
    }

    private fun selectDevice(
        host: String?,
        port: Int?,
        deviceId: String?
    ): SelectedDevice {
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
                        Maestro.ios(
                            driver = IOSDriver(IdbIOSDevice(channel, selectedDevice.device.instanceId)),
                            openDriver = !connectToExistingSession,
                        )
                    }
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
                ),
                device = null,
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
            openDriver = openDriver
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
    ): Maestro {
        val channel = ManagedChannelBuilder.forAddress(host ?: defaultHost, port ?: idbPort)
            .usePlaintext()
            .build()
        val device = PickDeviceInteractor.pickDevice(deviceId)
        val idbIOSDevice = IdbIOSDevice(channel, device.instanceId)
        val iosDriver = IOSDriver(idbIOSDevice)
        return Maestro.ios(
            driver = iosDriver,
            openDriver = openDriver,
        )
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
