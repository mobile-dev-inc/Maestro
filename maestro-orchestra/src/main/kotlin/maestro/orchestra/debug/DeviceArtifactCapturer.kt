package maestro.orchestra.debug

import kotlinx.coroutines.runBlocking
import maestro.Maestro
import maestro.device.CapturedDeviceArtifact
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Drives device-log + crash/ANR capture for one flow via [Maestro]. Best-effort:
 * any failure is logged and omitted, never failing the flow.
 */
internal class DeviceArtifactCapturer(
    private val maestro: Maestro,
    private val artifactsDir: Path,
) {
    fun start() {
        try {
            runBlocking { maestro.startDeviceLogCapture() }
        } catch (e: Exception) {
            logger.warn("Failed to start device log capture", e)
        }
    }

    fun collect(appId: String?, flowStartMs: Long): List<CapturedDeviceArtifact> {
        val dir = artifactsDir.toFile()
        val out = mutableListOf<CapturedDeviceArtifact>()
        try {
            out += runBlocking { maestro.stopAndCollectDeviceLogs(dir) }
        } catch (e: Exception) {
            logger.warn("Failed to collect device logs", e)
        }
        try {
            out += runBlocking { maestro.collectCrashArtifacts(appId, flowStartMs, dir) }
        } catch (e: Exception) {
            logger.warn("Failed to collect crash/ANR reports", e)
        }
        return out
    }

    companion object {
        private val logger = LoggerFactory.getLogger(DeviceArtifactCapturer::class.java)
    }
}
