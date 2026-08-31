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

package maestro.android

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

internal data class AdbServerDevice(
    val serial: String,
    val state: String,
) {
    val isOnline: Boolean
        get() = state == "device"
}

/**
 * Reads the adb server's device snapshot without constructing a dadb connection for every entry.
 *
 * dadb's list implementation constructs devices before exposing their transport state. A single
 * `unauthorized` or `offline` entry can therefore throw while being constructed and hide every
 * healthy device in the same snapshot. Keeping enumeration and connection creation separate lets
 * callers filter on `device` and isolate a race affecting one device from the rest of the list.
 */
internal object AdbServerDeviceDiscovery {

    private const val HOST_DEVICES_COMMAND = "host:devices"
    private const val IO_TIMEOUT_MS = 5_000

    fun list(host: String, port: Int): List<AdbServerDevice> =
        Socket().use { socket ->
            socket.soTimeout = IO_TIMEOUT_MS
            socket.connect(InetSocketAddress(host, port), IO_TIMEOUT_MS)
            query(
                input = DataInputStream(socket.getInputStream()),
                output = DataOutputStream(socket.getOutputStream()),
            )
        }

    internal fun query(
        input: DataInputStream,
        output: DataOutputStream,
    ): List<AdbServerDevice> {
        writeRequest(output, HOST_DEVICES_COMMAND)

        return when (val status = readString(input, 4)) {
            "OKAY" -> parse(readLengthPrefixedString(input))
            "FAIL" -> throw IOException("ADB device enumeration failed: ${readLengthPrefixedString(input)}")
            else -> throw IOException("Unexpected ADB device enumeration response: $status")
        }
    }

    internal fun parse(response: String): List<AdbServerDevice> =
        response.lineSequence()
            .mapNotNull { line ->
                val separator = line.indexOf('\t')
                if (separator <= 0) {
                    null
                } else {
                    val serial = line.substring(0, separator)
                    val state = line.substring(separator + 1).trim()
                    if (state.isEmpty()) null else AdbServerDevice(serial, state)
                }
            }
            .toList()

    internal fun <T> connectOnlineDevices(
        devices: List<AdbServerDevice>,
        connect: (String) -> T,
        onFailure: (AdbServerDevice, Throwable) -> Unit = { _, _ -> },
    ): List<T> =
        devices.asSequence()
            .filter { it.isOnline }
            .mapNotNull { device ->
                runCatching { connect(device.serial) }
                    .onFailure { onFailure(device, it) }
                    .getOrNull()
            }
            .toList()

    private fun writeRequest(output: DataOutputStream, command: String) {
        val commandBytes = command.toByteArray(StandardCharsets.UTF_8)
        val lengthBytes = String.format("%04x", commandBytes.size)
            .toByteArray(StandardCharsets.US_ASCII)
        output.write(lengthBytes)
        output.write(commandBytes)
        output.flush()
    }

    private fun readLengthPrefixedString(input: DataInputStream): String {
        val encodedLength = readString(input, 4)
        val length = encodedLength.toIntOrNull(16)
            ?: throw IOException("Invalid ADB response length: $encodedLength")
        return readString(input, length)
    }

    private fun readString(input: DataInputStream, length: Int): String {
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }
}
