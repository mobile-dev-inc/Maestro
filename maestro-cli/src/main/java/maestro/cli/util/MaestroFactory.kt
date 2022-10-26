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

package maestro.cli.util

import dadb.Dadb
import dadb.adbserver.AdbServer
import maestro.Maestro
import maestro.cli.device.Device
import maestro.cli.device.PickDeviceInteractor
import maestro.cli.device.Platform

object MaestroFactory {
    private const val defaultHost = "localhost"
    private const val idbPort = 10882

    fun createMaestro(host: String?, port: Int?, deviceId: String?): MaestroResult {
        if (host == null) {
            val device = PickDeviceInteractor.pickDevice(deviceId)

            return MaestroResult(
                maestro = when (device.platform) {
                    Platform.ANDROID -> {
                        Maestro.android(
                            Dadb
                                .list()
                                .find { it.toString() == device.instanceId }
                                ?: Dadb.discover()
                                ?: error("Unable to find device with id ${device.instanceId}")
                        )
                    }
                    Platform.IOS -> {
                        Maestro.ios(defaultHost, idbPort)
                    }
                },
                device = device,
            )
        }

        return MaestroResult(
            maestro = try {
                createAndroid(host, port)
            } catch (_: Exception) {
                try {
                    createIos(host, port)
                } catch (_: Exception) {
                    error("No devices found.")
                }
            },
            device = null
        )
    }

    private fun createAndroid(host: String?, port: Int?): Maestro {
        val dadb = if (port != null) {
            Dadb.create(host ?: defaultHost, port)
        } else {
            Dadb.discover(host ?: defaultHost)
                ?: createAdbServerDadb()
                ?: error("No android devices found.")
        }

        return Maestro.android(dadb)
    }

    private fun createAdbServerDadb(): Dadb? {
        return try {
            AdbServer.createDadb()
        } catch (ignored: Exception) {
            null
        }
    }

    private fun createIos(host: String?, port: Int?): Maestro {
        return Maestro.ios(host ?: defaultHost, port ?: idbPort)
    }

    data class MaestroResult(
        val maestro: Maestro,
        val device: Device?
    )

}
