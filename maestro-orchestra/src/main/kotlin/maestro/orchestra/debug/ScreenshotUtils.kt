package maestro.orchestra.debug

import kotlinx.coroutines.runBlocking
import maestro.Maestro
import okio.sink
import java.io.File

/**
 * Captures failure-time / lifecycle screenshots into a [FlowDebugOutput] for
 * later inclusion in the flow's debug bundle. Distinct from
 * [maestro.utils.ScreenshotUtils] (in `maestro-client`) which deals with
 * driver-level screenshot mechanics.
 *
 * Previously lived in `maestro-cli/maestro.cli.util.ScreenshotUtils`. Relocated
 * here so the orchestra-level [ArtifactsGenerator] (and any other consumer of
 * Orchestra) can share it without depending on the CLI module.
 */
object ScreenshotUtils {

    /**
     * Takes a screenshot of the current device state and appends it to
     * [debugOutput.screenshots]. Skips duplicate FAILED captures so parent
     * composite commands don't add their own failure screenshot on top of
     * the leaf command's.
     *
     * @param destFile If non-null, the screenshot is written here. If null,
     *   the screenshot goes to a temp file with `deleteOnExit()`. The non-null
     *   path is used by [ArtifactsGenerator] to write the canonical
     *   `screenshot-❌-<ts>.png` directly into the flow's artifacts directory
     *   without an extra copy.
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
            // Capture failed after the destination file was opened — clean it up
            // so we don't leak a zero-byte file in the artifacts directory.
            out.delete()
            null
        }
    }

    /**
     * Variant used by per-command lifecycle screenshot capture (CLI's
     * interactive runner records a screenshot at PENDING / COMPLETED stages).
     * Same shape as [takeDebugScreenshot] but without the duplicate-FAILED
     * dedup, and the filename includes the status word for uniqueness.
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
