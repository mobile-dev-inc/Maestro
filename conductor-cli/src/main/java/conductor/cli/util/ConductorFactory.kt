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

package conductor.cli.util

import conductor.Conductor
import conductor.utils.SocketUtils.isPortOpen
import dadb.Dadb

object ConductorFactory {

    fun createConductor(target: String?): Conductor {
        return when (target) {
            "android" -> {
                val dadb = Dadb.discover("localhost")
                if (dadb == null) {
                    println("No Android devices found")
                    throw IllegalStateException()
                }

                Conductor.android(dadb)
            }
            "ios" -> {
                Conductor.ios("localhost", 10882)
            }
            null -> findDevice()
            else -> throw IllegalStateException("Unknown target: $target")
        }
    }

    private fun findDevice(): Conductor {
        var device: Conductor? = null

        val dadb = Dadb.discover("localhost")
        if (dadb != null) {
            device = Conductor.android(dadb)
        }

        if (isPortOpen("localhost", 10882)) {
            if (device == null) {
                return Conductor.ios("localhost", 10882)
            } else {
                throw IllegalStateException("Multiple devices found. Specify one with --target")
            }
        }

        if (isPortOpen("localhost", 10883)) {
            if (device == null) {
                return Conductor.ios("localhost", 10883)
            } else {
                throw IllegalStateException("Multiple devices found. Specify one with --target")
            }
        }

        return device ?: throw IllegalStateException("No device found")
    }

}
