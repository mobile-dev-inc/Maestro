package maestro.web.record

import com.google.common.truth.Truth.assertThat
import okio.sink
import org.jcodec.common.io.NIOUtils
import org.jcodec.common.model.Packet
import org.jcodec.containers.mp4.demuxer.MP4Demuxer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * Chrome only sends a screencast frame when the page changes, so the video's timeline has to
 * come from when each frame arrived, not from a fixed frame rate. Each frame shows until the
 * next one, and the last one until the recording is finished.
 */
class JcodecVideoEncoderTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `each frame shows from its arrival until the next, the video runs from start to finish`() {
        val file = File(tempDir, "recording.mp4")
        val encoder = JcodecVideoEncoder()
        encoder.start(file.sink())

        encoder.encodeFrame(jpeg(Color.RED), atMs = 300)
        encoder.encodeFrame(jpeg(Color.BLUE), atMs = 10_000)
        encoder.finish(endMs = 12_000)

        val frames = demux(file)
        // The first frame is stretched back to 0:00 rather than starting at 0.3s.
        assertThat(frames.map { it.ptsD }).containsExactly(0.0, 10.0).inOrder()
        assertThat(frames.map { it.durationD }).containsExactly(10.0, 2.0).inOrder()
        assertThat(totalDurationSeconds(file)).isEqualTo(12.0)
    }

    @Test
    fun `frames with odd dimensions are encoded, trimmed to even ones`() {
        // Chrome scales the screencast to fit its bounds and can hand back an odd height; H264's
        // 4:2:0 chroma needs even dimensions.
        val file = File(tempDir, "recording.mp4")
        val encoder = JcodecVideoEncoder()
        encoder.start(file.sink())

        encoder.encodeFrame(jpeg(Color.RED, width = 63, height = 61), atMs = 0)
        encoder.finish(endMs = 1_000)

        val meta = NIOUtils.readableChannel(file).use { channel ->
            MP4Demuxer.createMP4Demuxer(channel).videoTracks.single().meta
        }
        assertThat(meta.totalFrames).isEqualTo(1)
        assertThat(meta.videoCodecMeta.size.width).isEqualTo(62)
        assertThat(meta.videoCodecMeta.size.height).isEqualTo(60)
    }

    @Test
    fun `finishing without any frame leaves an empty file for the caller to discard`() {
        val file = File(tempDir, "recording.mp4")
        val encoder = JcodecVideoEncoder()
        encoder.start(file.sink())

        encoder.finish(endMs = 3_000)

        assertThat(file.length()).isEqualTo(0L)
    }

    private fun jpeg(color: Color, width: Int = 64, height: Int = 64): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            this.color = color
            fillRect(0, 0, width, height)
            dispose()
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "jpeg", it) }.toByteArray()
    }

    private fun demux(file: File): List<Packet> =
        NIOUtils.readableChannel(file).use { channel ->
            val track = MP4Demuxer.createMP4Demuxer(channel).videoTracks.single()
            generateSequence { track.nextFrame() }.toList()
        }

    private fun totalDurationSeconds(file: File): Double =
        NIOUtils.readableChannel(file).use { channel ->
            MP4Demuxer.createMP4Demuxer(channel).videoTracks.single().meta.totalDuration
        }
}
