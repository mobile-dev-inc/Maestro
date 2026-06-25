package maestro.orchestra.debug

import kotlinx.coroutines.runBlocking
import maestro.Maestro
import okio.sink
import java.io.File

/**
 * Captures debug screenshots into a [FlowDebugOutput]. Distinct from
 * [maestro.utils.ScreenshotUtils] (driver-level mechanics).
 */
object ScreenshotUtils {

    /**
     * Screenshots the device into [destFile] (or a temp file when null), returning
     * the file or null when capture failed. Composite parent/leaf dedup is the
     * caller's concern — it owns the command sequence.
     */
    fun takeDebugScreenshot(maestro: Maestro, destFile: File? = null): File? {
        val out = destFile
            ?: File.createTempFile("screenshot-${System.currentTimeMillis()}", ".png")
                .also { it.deleteOnExit() }
        return kotlin.runCatching {
            runBlocking { maestro.takeScreenshot(out.sink(), false) }
            out
        }.getOrElse {
            out.delete() // don't leak a zero-byte file if capture threw mid-write
            null
        }
    }
}
