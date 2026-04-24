package maestro.cli.util

import java.io.File

/**
 * Persists the XCTest runner port per device, so subsequent Maestro
 * invocations can reuse the running runner instead of spawning a new
 * one (which would orphan the previous xcodebuild process).
 *
 * Stored at ~/.maestro/xctest-ports/<deviceId>.
 */
object XCTestPortStore {

    private val baseDir: File by lazy {
        File(System.getProperty("user.home"), ".maestro/xctest-ports").also { it.mkdirs() }
    }

    fun read(deviceId: String): Int? {
        return try {
            val file = portFile(deviceId)
            if (!file.exists()) return null
            file.readText().trim().toIntOrNull()
        } catch (e: Exception) {
            null
        }
    }

    fun write(deviceId: String, port: Int) {
        try {
            portFile(deviceId).writeText(port.toString())
        } catch (e: Exception) {
            // best effort — failure to persist just means the next run picks a new port
        }
    }

    fun clear(deviceId: String) {
        try {
            portFile(deviceId).delete()
        } catch (e: Exception) {
            // best effort
        }
    }

    private fun portFile(deviceId: String): File = File(baseDir, deviceId)
}
