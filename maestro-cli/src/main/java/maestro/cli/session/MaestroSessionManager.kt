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
import maestro.device.Device
import maestro.cli.device.PickDeviceInteractor
import maestro.device.Platform
import maestro.orchestra.devicecore.DeviceCoreDriver
import maestro.orchestra.devicecore.RealDeviceCoreDriver

object MaestroSessionManager {
    private const val defaultHost = "localhost"

    /**
     * The device-core `maestro test` provisioning path. Resolves the target through [selectDevice]
     * (so the right booted emulator/simulator is picked) and NEVER constructs a legacy
     * `maestro.Maestro`. Hands a connected-lifecycle [DeviceCoreDriver] and the resolved [Platform]
     * to [block] and closes the driver in a `finally`.
     *
     * Callers `connect()` the driver inside [block] (the resolved [Platform] and the resolved device
     * serial are what they name the [maestro.orchestra.devicecore.DeviceCoreTarget] with).
     * Provisioning is split into [provisionDeviceCore] so the core is unit-testable without a live
     * device.
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
     * The provisioning core: build a [DeviceCoreDriver] via [driverFactory], run [block], and close
     * the driver. `internal` so [maestro.cli.session.DeviceCoreSessionTest] can assert that directly.
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

    private fun createAdbServerConnection(
        driverHostPort: Int = AndroidDeviceConnection.DEFAULT_DRIVER_HOST_PORT,
    ): AndroidDeviceConnection? {
        return AndroidDeviceConnection.adbServer(adbServerPort = 5038, driverHostPort = driverHostPort)
    }

    private data class SelectedDevice(
        val platform: Platform,
        val device: Device.Connected? = null,
        val host: String? = null,
        val port: Int? = null,
        val deviceId: String? = null,
        val deviceType: Device.DeviceType,
    )
}
