package maestro.cli.util

import maestro.Maestro
import maestro.orchestra.debug.CommandStatus
import maestro.orchestra.debug.FlowDebugOutput
import okio.Buffer
import java.io.File
import maestro.orchestra.debug.ScreenshotUtils as OrchestraScreenshotUtils

/**
 * CLI-level screenshot utility. The failure-screenshot capture
 * (`takeDebugScreenshot`, `takeDebugScreenshotByCommand`) lives in
 * [maestro.orchestra.debug.ScreenshotUtils] now so the orchestra-level
 * `ArtifactsGenerator` and any other Orchestra consumer can share it
 * without depending on the CLI module. The methods here are thin
 * delegates kept for backwards compatibility with existing CLI callers
 * (`MaestroCommandRunner`, etc.).
 *
 * [writeAIscreenshot] is genuinely CLI-only (used by the
 * `onCommandGeneratedOutput` AI-defect path) and stays here.
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
