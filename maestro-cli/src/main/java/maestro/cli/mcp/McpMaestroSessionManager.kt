package maestro.cli.mcp

import maestro.cli.CliError
import maestro.cli.mcp.viewer.McpViewerEvents
import maestro.cli.mcp.viewer.StreamDeviceType
import maestro.cli.mcp.viewer.ViewerEvent
import maestro.device.DeviceService
import maestro.device.Device
import maestro.device.Platform
import maestro.orchestra.devicecore.DeviceCoreDriver
import maestro.orchestra.devicecore.DeviceCoreTarget
import maestro.orchestra.devicecore.RealDeviceCoreDriver
import java.util.concurrent.ConcurrentHashMap

internal class McpMaestroSessionManager(
    // Builds the device-core driver for a session. Defaults to the real driver; tests inject a fake.
    private val driverFactory: () -> DeviceCoreDriver = { RealDeviceCoreDriver() },
) : AutoCloseable {
    private val sessions = ConcurrentHashMap<String, McpMaestroSession>()

    fun <T> withSession(
        deviceId: String,
        block: (McpMaestroSession) -> T,
    ): T {
        val session = sessions.computeIfAbsent(deviceId) {
            createSession(deviceId).also { publishConnected(it) }
        }
        return block(session)
    }

    private fun publishConnected(session: McpMaestroSession) {
        McpViewerEvents.publish(
            ViewerEvent.MaestroConnected(
                platform = session.platform,
                deviceType = session.streamDeviceType,
                deviceId = session.deviceId,
            )
        )
    }

    override fun close() {
        sessions.values.forEach { session ->
            runCatching { session.close() }
        }
        sessions.clear()
    }

    // W4: the MCP session now carries a connected device-core driver instead of a legacy `Maestro`
    // facade. `run` executes through the converged Orchestra over this driver (see
    // McpViewerOrchestra); `inspect_screen` / `take_screenshot` route their device reads to the seam,
    // which throws NotImplemented until device-core ships hierarchy/screenshot. Web has no device-core
    // provider, so connecting a web target throws NotImplemented at the seam — the intended coverage
    // signal, surfaced back to the MCP caller as a tool error.
    private fun createSession(deviceId: String): McpMaestroSession {
        if (deviceId == WEB_DEVICE_ID) {
            return connectSession(
                platform = Platform.WEB,
                serial = null,
                deviceId = WEB_DEVICE_ID,
                platformName = "web",
                streamDeviceType = null,
            )
        }

        val device = DeviceService.listConnectedDevices()
            .find { it.instanceId.equals(deviceId, ignoreCase = true) }
            ?: throw CliError("Device with id $deviceId is not connected")
        val streamDeviceType = StreamDeviceType.forConnected(device)
            ?: throw UnsupportedOperationException(
                "Device ${device.instanceId} (${device.platform}/${device.deviceType}) is not supported by the MCP server"
            )

        return connectSession(
            platform = device.platform,
            serial = device.instanceId,
            deviceId = device.instanceId,
            platformName = platformName(device.platform),
            streamDeviceType = streamDeviceType,
        )
    }

    private fun connectSession(
        platform: Platform,
        serial: String?,
        deviceId: String,
        platformName: String,
        streamDeviceType: StreamDeviceType?,
    ): McpMaestroSession {
        val driver = driverFactory()
        // Connect once per device; subsequent tool calls reuse the cached, connected driver.
        driver.connect(DeviceCoreTarget(platform, serial), null)
        return McpMaestroSession(
            driver = driver,
            platform = platformName,
            streamDeviceType = streamDeviceType,
            deviceId = deviceId,
        )
    }

    private fun platformName(platform: Platform): String = when (platform) {
        Platform.ANDROID -> "android"
        Platform.IOS -> "ios"
        Platform.WEB -> "web"
    }

    data class McpMaestroSession(
        // The session-provisioned, connected device-core driver every MCP tool drives device ops through.
        val driver: DeviceCoreDriver,
        val platform: String,
        // null for web sessions, which the viewer doesn't stream.
        val streamDeviceType: StreamDeviceType?,
        val deviceId: String,
    ) {
        fun close() {
            driver.close()
        }
    }

    private companion object {
        private const val WEB_DEVICE_ID = "chromium"
    }
}
