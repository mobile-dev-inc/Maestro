package maestro.cli.util

import okio.Buffer
import java.io.File

/** CLI-only screenshot helper: writes an AI-generated screenshot buffer to a temp file. */
object ScreenshotUtils {

    fun writeAIscreenshot(buffer: Buffer): File {
        val out = File
            .createTempFile("ai-screenshot-${System.currentTimeMillis()}", ".png")
            .also { it.deleteOnExit() }
        out.outputStream().use { it.write(buffer.readByteArray()) }
        return out
    }
}
