package maestro.orchestra.debug

import maestro.device.CapturedDeviceArtifact
import maestro.orchestra.devicecore.DeviceGateway
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Drives device-log + crash/ANR capture for one flow through the [DeviceGateway] seam.
 * Best-effort: any failure — including a roadmap backend that surfaces NotImplemented — is logged
 * and omitted, never failing the flow.
 */
internal class DeviceArtifactCapturer(
    private val driver: DeviceGateway,
    private val outputDir: Path,
) {
    fun start() {
        try {
            driver.startDeviceLogCapture()
        } catch (e: Exception) {
            logger.warn("Failed to start device log capture", e)
        }
    }

    fun collect(appId: String?, flowStartMs: Long): List<CapturedDeviceArtifact> {
        val dir = outputDir.toFile()
        val out = mutableListOf<CapturedDeviceArtifact>()
        try {
            out += driver.stopAndCollectDeviceLogs(dir)
        } catch (e: Exception) {
            logger.warn("Failed to collect device logs", e)
        }
        try {
            out += driver.collectCrashArtifacts(appId, flowStartMs, dir)
        } catch (e: Exception) {
            logger.warn("Failed to collect crash/ANR reports", e)
        }
        return out
    }

    companion object {
        private val logger = LoggerFactory.getLogger(DeviceArtifactCapturer::class.java)
    }
}
