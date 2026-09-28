package maestro.web.record

import maestro.utils.TempFileHandler
import okio.Sink
import okio.buffer
import okio.source
import org.jcodec.api.transcode.PixelStore.LoanerPicture
import org.jcodec.api.transcode.SinkImpl
import org.jcodec.api.transcode.VideoFrameWithPacket
import org.jcodec.common.Codec
import org.jcodec.common.Format
import org.jcodec.common.io.NIOUtils
import org.jcodec.common.model.ColorSpace
import org.jcodec.common.model.Packet
import org.jcodec.common.model.Packet.FrameType
import org.jcodec.common.model.Picture
import org.jcodec.scale.AWTUtil
import org.jcodec.scale.ColorUtil
import org.jcodec.scale.Transform
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * H264-in-MP4 via jcodec, with per-frame durations rather than a fixed frame rate. This is what
 * `SequenceEncoder` does internally, minus its assumption that frames are evenly spaced.
 *
 * A frame's duration is only known once the next frame (or the end) arrives, so one decoded
 * frame is held back and written when its end time is known.
 */
class JcodecVideoEncoder : VideoEncoder {

    private val tempFiles = TempFileHandler()
    private lateinit var tempFile: File
    private lateinit var sink: SinkImpl
    private var transform: Transform? = null
    private lateinit var out: Sink

    private class HeldFrame(val picture: Picture, val startMs: Long)

    private var held: HeldFrame? = null
    private var frameNo = 0L
    private var writtenUntilMs = 0L

    override fun start(out: Sink) {
        tempFile = tempFiles.createTempFile("maestro_jcodec", ".mp4")

        sink = SinkImpl.createWithStream(NIOUtils.writableChannel(tempFile), Format.MOV, Codec.H264, null)
        sink.init()
        transform = sink.inputColor?.let { ColorUtil.getTransform(ColorSpace.RGB, it) }

        this.out = out
    }

    override fun encodeFrame(frame: ByteArray, atMs: Long) {
        val image = ByteArrayInputStream(frame).use { ImageIO.read(it) }
        // H264's 4:2:0 chroma needs even dimensions; Chrome scales the screencast to fit its
        // bounds and can hand back an odd edge. Trim a pixel rather than fail the recording.
        val evenImage = image.getSubimage(0, 0, image.width and 1.inv(), image.height and 1.inv())
        val picture = AWTUtil.fromBufferedImageRGB(evenImage)

        writeHeld(untilMs = atMs)
        // A frame can never start before the previous one ended.
        held = HeldFrame(picture, maxOf(atMs, writtenUntilMs))
    }

    override fun finish(endMs: Long) {
        try {
            try {
                writeHeld(untilMs = endMs)
                sink.finish()
            } catch (e: Throwable) {
                out.close()
                throw e
            }
            // With no frames jcodec still writes a header-only file; the caller expects an empty
            // output for a recording that captured nothing, so write nothing.
            out.buffer().use { dst -> if (frameNo > 0) tempFile.source().buffer().use { dst.writeAll(it) } }
        } finally {
            tempFiles.close()
        }
    }

    private fun writeHeld(untilMs: Long) {
        val frame = held ?: return
        // The first frame is stretched back to cover the time before it arrived, so that video
        // 0:00 is the recording's start rather than the first page change.
        val startMs = if (frameNo == 0L) 0L else frame.startMs
        val durationMs = maxOf(untilMs - startMs, 1L)

        val toEncode = transform?.let { t ->
            Picture.create(frame.picture.width, frame.picture.height, sink.inputColor).also { t.transform(frame.picture, it) }
        } ?: frame.picture

        val packet = Packet.createPacket(null, startMs, TIMESCALE, durationMs, frameNo, FrameType.KEY, null)
        sink.outputVideoFrame(VideoFrameWithPacket(packet, LoanerPicture(toEncode, 0)))

        frameNo++
        writtenUntilMs = startMs + durationMs
        held = null
    }

    private companion object {
        /** Milliseconds, so packet times need no conversion. */
        const val TIMESCALE = 1000
    }
}
