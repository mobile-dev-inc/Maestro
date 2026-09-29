package maestro.cli.graphics

import com.google.common.truth.Truth.assertThat
import maestro.cli.runner.resultview.AnsiResultView
import org.jcodec.api.awt.AWTSequenceEncoder
import org.jcodec.common.io.NIOUtils
import org.jcodec.common.model.Rational
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Path
import java.util.Base64

class LocalVideoRendererTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `local renderer writes a non-empty mp4`() {
        val source = tempDir.resolve("source.mp4").toFile()
        val output = tempDir.resolve("out.mp4").toFile()
        writeSolidSource(source, frameCount = 5, fps = 5)

        LocalVideoRenderer(
            frameRenderer = SkiaFrameRenderer(),
            outputFile = output,
            outputFPS = 5,
            outputWidthPx = 640,
            outputHeightPx = 368,
        ).render(source, transcript("first\n"))

        assertThat(output.length()).isGreaterThan(0L)
    }

    @Test
    fun `unchanged frames are reused until the transcript changes`() {
        val source = tempDir.resolve("static.mp4").toFile()
        val output = tempDir.resolve("reused.mp4").toFile()
        writeSolidSource(source, frameCount = 3, fps = 2)

        val counting = CountingFrameRenderer()
        LocalVideoRenderer(
            frameRenderer = counting,
            outputFile = output,
            outputFPS = 5,
            outputWidthPx = 320,
            outputHeightPx = 176,
        ).render(source, twoTranscripts())

        assertThat(counting.draws).isEqualTo(2)
        assertThat(output.length()).isGreaterThan(0L)
    }

    @Test
    fun `composites a static transcript once`() {
        val source = tempDir.resolve("once.mp4").toFile()
        val output = tempDir.resolve("once-out.mp4").toFile()
        writeSolidSource(source, frameCount = 3, fps = 2)

        val counting = CountingFrameRenderer()
        LocalVideoRenderer(
            frameRenderer = counting,
            outputFile = output,
            outputFPS = 5,
            outputWidthPx = 320,
            outputHeightPx = 176,
        ).render(source, transcript("first\n"))

        assertThat(counting.draws).isEqualTo(1)
        assertThat(output.length()).isGreaterThan(0L)
    }

    private fun transcript(text: String): List<AnsiResultView.Frame> {
        return listOf(
            AnsiResultView.Frame(
                timestamp = 0,
                content = Base64.getEncoder().encodeToString(text.toByteArray()),
            )
        )
    }

    private fun twoTranscripts(): List<AnsiResultView.Frame> {
        return transcript("first\n") + AnsiResultView.Frame(
            timestamp = 400,
            content = Base64.getEncoder().encodeToString("second\n".toByteArray()),
        )
    }

    private fun writeSolidSource(file: File, frameCount: Int, fps: Int) {
        NIOUtils.writableFileChannel(file.absolutePath).use { out ->
            AWTSequenceEncoder(out, Rational.R(fps, 1)).use { encoder ->
                val image = BufferedImage(80, 160, BufferedImage.TYPE_3BYTE_BGR)
                val graphics = image.graphics
                graphics.color = Color.BLUE
                graphics.fillRect(0, 0, 80, 160)
                repeat(frameCount) {
                    encoder.encodeImage(image)
                }
                graphics.dispose()
            }
        }
    }

    private class CountingFrameRenderer : FrameRenderer {
        private val delegate = SkiaFrameRenderer()
        var draws = 0
            private set

        override fun render(
            outputWidthPx: Int,
            outputHeightPx: Int,
            screen: BufferedImage,
            text: String,
        ): BufferedImage {
            draws += 1
            return delegate.render(outputWidthPx, outputHeightPx, screen, text)
        }
    }
}
