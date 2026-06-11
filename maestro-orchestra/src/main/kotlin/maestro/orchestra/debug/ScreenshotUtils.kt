package maestro.orchestra.debug

import kotlinx.coroutines.runBlocking
import maestro.Maestro
import okio.sink
import java.io.File

/**
 * Captures failure-time / lifecycle screenshots into a [FlowDebugOutput].
 * Lives in orchestra (not maestro-cli) so [ArtifactsGenerator] and other
 * Orchestra consumers can share it. Distinct from [maestro.utils.ScreenshotUtils]
 * (driver-level mechanics).
 */
object ScreenshotUtils {

    /**
     * Screenshots the device and appends it to [debugOutput]. Skips a duplicate
     * FAILED capture so a parent composite doesn't stack one on the leaf's.
     *
     * @param destFile written here if non-null (used by [ArtifactsGenerator] to
     *   land `screenshot-❌-<ts>.png` directly in the artifacts dir); otherwise a
     *   temp file with `deleteOnExit()`.
     */
    fun takeDebugScreenshot(
        maestro: Maestro,
        debugOutput: FlowDebugOutput,
        status: CommandStatus,
        destFile: File? = null,
    ): File? {
        // Avoid duplicate FAILED screenshots from parent composite commands.
        val containsFailed = debugOutput.screenshots.any { it.status == CommandStatus.FAILED }
        if (containsFailed && status == CommandStatus.FAILED) {
            return null
        }

        val out = destFile
            ?: File.createTempFile("screenshot-${System.currentTimeMillis()}", ".png")
                .also { it.deleteOnExit() }
        return kotlin.runCatching {
            runBlocking { maestro.takeScreenshot(out.sink(), false) }
            debugOutput.screenshots.add(
                FlowDebugOutput.Screenshot(
                    screenshot = out,
                    timestamp = System.currentTimeMillis(),
                    status = status,
                )
            )
            out
        }.getOrElse {
            out.delete() // don't leak a zero-byte file if capture threw mid-write
            null
        }
    }

    /**
     * Lifecycle-stage variant (CLI interactive runner, PENDING/COMPLETED): no
     * duplicate-FAILED dedup, status word in the filename for uniqueness.
     */
    fun takeDebugScreenshotByCommand(
        maestro: Maestro,
        debugOutput: FlowDebugOutput,
        status: CommandStatus,
    ): File? {
        return kotlin.runCatching {
            val out = File
                .createTempFile("screenshot-${status}-${System.currentTimeMillis()}", ".png")
                .also { it.deleteOnExit() }
            runBlocking { maestro.takeScreenshot(out.sink(), false) }
            debugOutput.screenshots.add(
                FlowDebugOutput.Screenshot(
                    screenshot = out,
                    timestamp = System.currentTimeMillis(),
                    status = status,
                )
            )
            out
        }.getOrNull()
    }
}
