package maestro.cli.graphics

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import maestro.cli.runner.resultview.Frame
import okio.ByteString.Companion.encodeUtf8
import org.jcodec.api.awt.AWTSequenceEncoder
import org.jcodec.common.io.NIOUtils
import org.jcodec.common.model.Packet
import org.jcodec.common.model.Rational
import org.jcodec.containers.mp4.demuxer.MP4Demuxer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File

class LocalVideoRendererTest {

    @TempDir
    lateinit var dir: File

    // Four screen frames, half a second apart, and one change of text between the second and third.
    // The terminal's first output comes just after the recording starts, as it does in a real run.
    private val screenColors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.WHITE)
    private val textFrames = listOf(
        textFrame(atMs = 100, "✅ Launch app"),
        textFrame(atMs = 750, "✅ Launch app\n✅ Tap on \"Next\""),
    )

    @Test
    fun `draws a frame only when the screen or the terminal text changes`() {
        val frameRenderer = RecordingFrameRenderer()

        render(screenRecording(fps = 2, screenColors), frameRenderer)

        // One drawing per screen frame, plus one for the text change.
        assertThat(frameRenderer.drawn).containsExactly(
            "RED" to "✅ Launch app",
            "GREEN" to "✅ Launch app",
            "GREEN" to "✅ Launch app\n✅ Tap on \"Next\"",
            "BLUE" to "✅ Launch app\n✅ Tap on \"Next\"",
            "WHITE" to "✅ Launch app\n✅ Tap on \"Next\"",
        ).inOrder()
    }

    @Test
    fun `each frame is shown from its change until the next change`() {
        val output = render(screenRecording(fps = 2, screenColors), RecordingFrameRenderer())

        val frames = demux(output)
        assertThat(frames.map { it.ptsD }).containsExactly(0.0, 0.5, 0.75, 1.0, 1.5).inOrder()
        // The last frame runs on past the 2s recording until the final terminal output, at 0.75s, has been
        // on show for 2s.
        assertThat(frames.map { it.durationD }).containsExactly(0.5, 0.25, 0.25, 0.5, 1.25).inOrder()
    }

    @Test
    fun `a screen changing faster than the frame rate is sampled at the frame rate`() {
        val frameRenderer = RecordingFrameRenderer()
        val oneSecondAt50Fps = List(50) { if (it % 2 == 0) Color.RED else Color.BLUE }

        render(screenRecording(fps = 50, oneSecondAt50Fps), frameRenderer, maxFps = 25)

        assertThat(frameRenderer.drawn.size).isAtMost(26)
    }

    @Test
    fun `holds the last screen until the final terminal output has been on show for two seconds`() {
        val frameRenderer = RecordingFrameRenderer()
        // The recording is 2s long; the last command finishes a second after it ends.
        val lateTexts = textFrames + textFrame(atMs = 3_000, "✅ Assert that \"Done\" is visible")

        val output = render(screenRecording(fps = 2, screenColors), frameRenderer, textFrames = lateTexts)

        assertThat(frameRenderer.drawn.last()).isEqualTo("WHITE" to "✅ Assert that \"Done\" is visible")
        val last = demux(output).last()
        assertThat(last.ptsD + last.durationD).isEqualTo(5.0)
    }

    @Test
    fun `renders the screen with an empty terminal when the terminal never rendered`() {
        val frameRenderer = RecordingFrameRenderer()

        render(screenRecording(fps = 2, screenColors), frameRenderer, textFrames = emptyList())

        assertThat(frameRenderer.drawn).containsExactly(
            "RED" to "",
            "GREEN" to "",
            "BLUE" to "",
            "WHITE" to "",
        ).inOrder()
    }

    @Test
    fun `a render that fails partway leaves a video of the frames before the failure`() {
        val output = File(dir, "out.mp4")
        val failingOnThirdFrame = object : FrameRenderer {
            var frames = 0
            override fun render(outputWidthPx: Int, outputHeightPx: Int, screen: BufferedImage, text: String): BufferedImage {
                check(++frames < 3) { "Rendering failed" }
                return solid(outputWidthPx, outputHeightPx, Color.BLACK)
            }
        }

        assertThrows<IllegalStateException> {
            LocalVideoRenderer(failingOnThirdFrame, output, maxFPS = 25, outputWidthPx = 64, outputHeightPx = 36)
                .render(screenRecording(fps = 2, screenColors), textFrames)
        }

        // Checked before demuxing: jcodec's demuxer, given a file with no index, can exhaust the
        // heap, and the test is then reported as skipped rather than failed.
        assertWithMessage("MP4 index (moov box)").that(String(output.readBytes(), Charsets.ISO_8859_1)).contains("moov")
        assertThat(demux(output).map { it.ptsD }).containsExactly(0.0, 0.5).inOrder()
    }

    private fun render(
        screenRecording: File,
        frameRenderer: FrameRenderer,
        maxFps: Int = 25,
        textFrames: List<Frame> = this.textFrames,
    ): File {
        val output = File(dir, "out.mp4")
        LocalVideoRenderer(frameRenderer, output, maxFPS = maxFps, outputWidthPx = 64, outputHeightPx = 36)
            .render(screenRecording, textFrames)
        return output
    }

    private fun screenRecording(fps: Int, colors: List<Color>): File {
        val file = File(dir, "screen.mp4")
        NIOUtils.writableFileChannel(file.absolutePath).use { out ->
            val encoder = AWTSequenceEncoder(out, Rational.R(fps, 1))
            colors.forEach { encoder.encodeImage(solid(64, 64, it)) }
            encoder.finish()
        }
        return file
    }

    private fun textFrame(atMs: Long, text: String) = Frame(atMs, text.encodeUtf8().base64())

    private fun demux(file: File): List<Packet> =
        NIOUtils.readableChannel(file).use { channel ->
            val track = MP4Demuxer.createMP4Demuxer(channel).videoTracks.single()
            generateSequence { track.nextFrame() }.toList()
        }

    /** Records, for each frame it draws, the screen's color and the terminal text it was given. */
    private inner class RecordingFrameRenderer : FrameRenderer {
        val drawn = mutableListOf<Pair<String, String>>()

        override fun render(outputWidthPx: Int, outputHeightPx: Int, screen: BufferedImage, text: String): BufferedImage {
            drawn += colorName(Color(screen.getRGB(screen.width / 2, screen.height / 2))) to text
            return solid(outputWidthPx, outputHeightPx, Color.BLACK)
        }
    }

    /** The nearest of the screen colors, since the screen recording is lossy. */
    private fun colorName(color: Color): String {
        val nearest = screenColors.minBy {
            Math.abs(it.red - color.red) + Math.abs(it.green - color.green) + Math.abs(it.blue - color.blue)
        }
        return mapOf(Color.RED to "RED", Color.GREEN to "GREEN", Color.BLUE to "BLUE", Color.WHITE to "WHITE").getValue(nearest)
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
