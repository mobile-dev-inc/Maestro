package maestro.cli.graphics

import com.google.common.truth.Truth.assertThat
import maestro.cli.runner.resultview.Frame
import okio.ByteString.Companion.encodeUtf8
import org.jcodec.api.awt.AWTSequenceEncoder
import org.jcodec.common.io.NIOUtils
import org.jcodec.common.model.Rational
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File

class LocalVideoRendererTest {

    @Test
    fun `draws a frame only when the screen or the terminal text changes`(@TempDir dir: File) {
        // Four screen frames, half a second apart, and one change of text between the second and third.
        val screenRecording = screenRecording(File(dir, "screen.mp4"), Color.RED, Color.GREEN, Color.BLUE, Color.WHITE)
        val textFrames = listOf(textFrame(atMs = 0, "✅ Launch app"), textFrame(atMs = 750, "✅ Launch app\n✅ Tap on \"Next\""))
        val frameRenderer = CountingFrameRenderer()

        LocalVideoRenderer(frameRenderer, File(dir, "out.mp4"), outputFPS = 25, outputWidthPx = 64, outputHeightPx = 36)
            .render(screenRecording, textFrames)

        // About 50 output frames, but only 5 distinct ones: one per screen frame, plus the text change.
        assertThat(frameRenderer.texts).containsExactly(
            "✅ Launch app",
            "✅ Launch app",
            "✅ Launch app\n✅ Tap on \"Next\"",
            "✅ Launch app\n✅ Tap on \"Next\"",
            "✅ Launch app\n✅ Tap on \"Next\"",
        ).inOrder()
    }

    private fun screenRecording(file: File, vararg colors: Color): File {
        NIOUtils.writableFileChannel(file.absolutePath).use { out ->
            val encoder = AWTSequenceEncoder(out, Rational.R(2, 1))
            colors.forEach { encoder.encodeImage(solid(64, 64, it)) }
            encoder.finish()
        }
        return file
    }

    private fun textFrame(atMs: Long, text: String) = Frame(atMs, text.encodeUtf8().base64())

    private class CountingFrameRenderer : FrameRenderer {
        val texts = mutableListOf<String>()

        override fun render(outputWidthPx: Int, outputHeightPx: Int, screen: BufferedImage, text: String): BufferedImage {
            texts += text
            return solid(outputWidthPx, outputHeightPx, Color.BLACK)
        }
    }

    private companion object {
        fun solid(width: Int, height: Int, color: Color) =
            BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR).apply {
                createGraphics().use { g ->
                    g.color = color
                    g.fillRect(0, 0, width, height)
                }
            }
    }
}
