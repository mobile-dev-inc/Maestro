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
    private const val defaultHost = "localhost"
    private const val idbPort = 10882

    fun createMaestro(platform: String?, host: String?, port: Int?): Maestro {

        return when (platform) {
            null -> { autoDetectPlatform(host, port) }
            "android" -> {
                createAndroid(host, port)
            }
            "ios" -> {
                createIos(host, port)
            }
            else -> {
                throw IllegalStateException("Unknown platform: $platform")
            }
        }
    }

    private fun createAndroid(host: String?, port: Int?): Maestro {
        val dadb = if (port != null) {
            Dadb.create(host ?: defaultHost, port)
        } else {
            Dadb.discover(host ?: defaultHost)
                ?: throw IllegalStateException("No android devices found.")
        }

        return Maestro.android(dadb)
    }

    private fun createIos(host: String?, port: Int?): Maestro {
        return Maestro.ios(host ?: defaultHost, port ?: idbPort)
    }

    private fun autoDetectPlatform(host: String?, port: Int?): Maestro {
        return try {
            createAndroid(host, port)
        } catch (_: Exception) {
            try {
                createIos(host, port)
            } catch (_: Exception) {
                throw IllegalStateException("No devices found. Select a platform by passing --platform <android|ios> for more details")
            }
        }
    }
}
