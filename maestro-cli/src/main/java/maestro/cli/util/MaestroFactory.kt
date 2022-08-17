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

import maestro.Maestro
import maestro.utils.SocketUtils.isPortOpen
import dadb.Dadb

object MaestroFactory {

    @Suppress("NAME_SHADOWING")
    fun createMaestro(platform: String?, host: String?, port: Int?): Maestro {
        val host = host ?: "localhost"
        val port = port ?: 10882

        return when (platform) {
            null -> {
                try {
                    connectAndroid(host)
                } catch (e: Exception) {
                    connectIos(host, port)
                }
            }
            "android" -> {
                connectAndroid(host)
            }
            "ios" -> {
                connectIos(host, port)
            }
            else -> {
                throw IllegalStateException("Unknown platform: $platform")
            }
        }
    }

    private fun connectAndroid(host: String): Maestro {
        val dadb = Dadb.discover(host)
        return if (dadb != null) {
            Maestro.android(dadb)
        } else {
            println("No Android devices found")
            throw IllegalStateException()
        }
    }

    private fun connectIos(host: String, port: Int): Maestro {
        return Maestro.ios(host, port)
    }
}
