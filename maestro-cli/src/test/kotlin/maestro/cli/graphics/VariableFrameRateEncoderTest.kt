package maestro.cli.graphics

import com.google.common.truth.Truth.assertThat
import org.jcodec.api.FrameGrab
import org.jcodec.common.io.NIOUtils
import org.jcodec.common.model.Packet
import org.jcodec.common.model.Packet.FrameType
import org.jcodec.containers.mp4.demuxer.MP4Demuxer
import org.jcodec.scale.AWTUtil
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File

/**
 * Keyframes are placed by time rather than by frame count, as `ffmpeg -force_key_frames` does
 * for variable frame rate video: a frame is a keyframe when two seconds or more have passed since
 * the last one, so seeking never has far to decode, however many or few frames that covers.
 */
class VariableFrameRateEncoderTest {

    @TempDir
    lateinit var dir: File

    private val colors = listOf(Color.RED, Color.GREEN, Color.BLUE)

    @Test
    fun `a keyframe every two seconds, however many frames fall between`() {
        // 125 frames, 40ms apart: 50 frames between keyframes, more than jcodec's own 25.
        val video = encode((0 until 125).map { i -> Frame(colors[i % 3], startMs = i * 40L, durationMs = 40) })

        assertThat(keyFrameTimes(video)).containsExactly(0.0, 2.0, 4.0).inOrder()
    }

    @Test
    fun `the first change after a long still frame is a keyframe`() {
        val video = encode(
            listOf(
                Frame(Color.RED, startMs = 0, durationMs = 1_000),
                Frame(Color.GREEN, startMs = 1_000, durationMs = 9_000),
                Frame(Color.BLUE, startMs = 10_000, durationMs = 500),
                Frame(Color.RED, startMs = 10_500, durationMs = 500),
            ),
        )

        assertThat(keyFrameTimes(video)).containsExactly(0.0, 10.0).inOrder()
    }

    @Test
    fun `every frame decodes to the picture it was given`() {
        val frames = (0 until 125).map { i -> Frame(colors[i % 3], startMs = i * 40L, durationMs = 40) }

        val video = encode(frames)

        assertThat(decodedColors(video)).containsExactlyElementsIn(frames.map { it.color }).inOrder()
    }

    private data class Frame(val color: Color, val startMs: Long, val durationMs: Long)

    private fun encode(frames: List<Frame>): File {
        val file = File(dir, "out.mp4")
        NIOUtils.writableFileChannel(file.absolutePath).use { out ->
            val encoder = VariableFrameRateEncoder(out)
            frames.forEach { encoder.encode(AWTUtil.fromBufferedImageRGB(solid(it.color)), it.startMs, it.durationMs) }
            encoder.finish()
        }
        return file
    }

    private fun keyFrameTimes(file: File): List<Double> = demux(file).filter { it.frameType == FrameType.KEY }.map { it.ptsD }

    private fun demux(file: File): List<Packet> =
        NIOUtils.readableChannel(file).use { channel ->
            val track = MP4Demuxer.createMP4Demuxer(channel).videoTracks.single()
            generateSequence { track.nextFrame() }.toList()
        }

    /** Decodes each frame and names it by the nearest of [colors], since H264 is lossy. */
    private fun decodedColors(file: File): List<Color> =
        NIOUtils.readableChannel(file).use { channel ->
            val grab = FrameGrab.createFrameGrab(channel)
            generateSequence { grab.nativeFrame }
                .map { Color(AWTUtil.toBufferedImage(it).getRGB(32, 32)) }
                .map { decoded -> colors.minBy { Math.abs(it.red - decoded.red) + Math.abs(it.green - decoded.green) + Math.abs(it.blue - decoded.blue) } }
                .toList()
        }

    private fun solid(color: Color) =
        BufferedImage(64, 64, BufferedImage.TYPE_3BYTE_BGR).apply {
            createGraphics().use { g ->
                g.color = color
                g.fillRect(0, 0, 64, 64)
            }
        }
}
