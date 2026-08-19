package maestro.orchestra.debug

import maestro.orchestra.devicecore.DeviceGateway
import okio.sink
import java.io.File

/**
 * Captures debug screenshots into a [FlowDebugOutput]. Distinct from
 * [maestro.utils.ScreenshotUtils] (driver-level mechanics).
 */
object ScreenshotUtils {

    /**
     * Screenshots the device into [destFile] (or a temp file when null) via the [DeviceGateway]
     * seam, returning the file or null when capture failed (a roadmap backend surfaces
     * NotImplemented, which counts as a failed capture here). Composite parent/leaf dedup is the
     * caller's concern — it owns the command sequence.
     */
    fun takeDebugScreenshot(driver: DeviceGateway, destFile: File? = null): File? {
        val out = destFile
            ?: File.createTempFile("screenshot-${System.currentTimeMillis()}", ".png")
                .also { it.deleteOnExit() }
        return kotlin.runCatching {
            driver.takeScreenshot(out.sink(), false)
            out
        }.getOrElse {
            out.delete() // don't leak a zero-byte file if capture threw mid-write
            null
        }
    }
}
