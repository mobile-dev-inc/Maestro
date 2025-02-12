package maestro.cli.util

import java.io.File
import maestro.Maestro
import maestro.cli.report.FlowDebugOutput
import maestro.cli.runner.CommandStatus
import okio.Buffer
import okio.sink

object ScreenshotUtils {

    fun takeDebugScreenshot(maestro: Maestro, debugOutput: FlowDebugOutput, status: CommandStatus): File? {
        val containsFailed = debugOutput.screenshots.any { it.status == CommandStatus.FAILED }

        // Avoids duplicate failed images from parent commands
        if (containsFailed && status == CommandStatus.FAILED) {
            return null
        }

        val result = kotlin.runCatching {
            val out = File
                .createTempFile("screenshot-${System.currentTimeMillis()}", ".png")
                .also { it.deleteOnExit() } // save to another dir before exiting
            maestro.takeScreenshot(out.sink(), false)
            debugOutput.screenshots.add(
                FlowDebugOutput.Screenshot(
                    screenshot = out,
                    timestamp = System.currentTimeMillis(),
                    status = status
                )
            )
            out
        }

        return result.getOrNull()
    }

    fun takeDebugScreenshotByCommand(maestro: Maestro, debugOutput: FlowDebugOutput, status: CommandStatus): File? {
        val result = kotlin.runCatching {
            val out = File
                .createTempFile("screenshot-${status}-${System.currentTimeMillis()}", ".png")
                .also { it.deleteOnExit() } // save to another dir before exiting
            maestro.takeScreenshot(out.sink(), false)
            debugOutput.screenshots.add(
                FlowDebugOutput.Screenshot(
                    screenshot = out,
                    timestamp = System.currentTimeMillis(),
                    status = status
                )
            )
            out
        }

        return result.getOrNull()
    }

    fun writeAIscreenshot(buffer: Buffer): File {
        val out = File
            .createTempFile("ai-screenshot-${System.currentTimeMillis()}", ".png")
            .also { it.deleteOnExit() }
        out.outputStream().use { it.write(buffer.readByteArray()) }
        return out
    }

}
