package maestro.cli.util

import maestro.Maestro
import maestro.orchestra.debug.CommandStatus
import maestro.orchestra.debug.FlowDebugOutput
import okio.Buffer
import java.io.File
import maestro.orchestra.debug.ScreenshotUtils as OrchestraScreenshotUtils

/**
 * CLI screenshot utility. The capture helpers moved to
 * [maestro.orchestra.debug.ScreenshotUtils] (shared with `ArtifactsGenerator`);
 * these are thin delegates for existing CLI callers. [writeAIscreenshot] is
 * CLI-only and stays here.
 */
object ScreenshotUtils {

    fun takeDebugScreenshot(maestro: Maestro, debugOutput: FlowDebugOutput, status: CommandStatus): File? =
        OrchestraScreenshotUtils.takeDebugScreenshot(maestro, debugOutput, status)

    fun takeDebugScreenshotByCommand(maestro: Maestro, debugOutput: FlowDebugOutput, status: CommandStatus): File? =
        OrchestraScreenshotUtils.takeDebugScreenshotByCommand(maestro, debugOutput, status)

    fun writeAIscreenshot(buffer: Buffer): File {
        val out = File
            .createTempFile("ai-screenshot-${System.currentTimeMillis()}", ".png")
            .also { it.deleteOnExit() }
        out.outputStream().use { it.write(buffer.readByteArray()) }
        return out
    }
}
