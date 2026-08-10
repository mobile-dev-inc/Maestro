package maestro.orchestra.debug

import kotlinx.coroutines.runBlocking
import maestro.device.CapturedDeviceArtifact
import maestro.orchestra.backend.ExecutionBackend
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Drives device-log + crash/ANR capture for one flow via the [ExecutionBackend] seam. Best-effort:
 * any failure is logged and omitted, never failing the flow.
 */
internal class DeviceArtifactCapturer(
    private val backend: ExecutionBackend,
    private val outputDir: Path,
) {
    fun start() {
        try {
            runBlocking { backend.startDeviceLogCapture() }
        } catch (e: Exception) {
            logger.warn("Failed to start device log capture", e)
        }
    }

    fun collect(appId: String?, flowStartMs: Long): List<CapturedDeviceArtifact> {
        val dir = outputDir.toFile()
        val out = mutableListOf<CapturedDeviceArtifact>()
        try {
            out += runBlocking { backend.stopAndCollectDeviceLogs(dir) }
        } catch (e: Exception) {
            logger.warn("Failed to collect device logs", e)
        }
        try {
            out += runBlocking { backend.collectCrashArtifacts(appId, flowStartMs, dir) }
        } catch (e: Exception) {
            logger.warn("Failed to collect crash/ANR reports", e)
        }
        return out
    }

    companion object {
        private val logger = LoggerFactory.getLogger(DeviceArtifactCapturer::class.java)
    }
}
