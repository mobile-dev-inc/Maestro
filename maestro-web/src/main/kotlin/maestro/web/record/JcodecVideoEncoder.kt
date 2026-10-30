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
import org.jcodec.common.io.FileChannelWrapper
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
 * frame is held back and written when its end time is known. Frames are laid end to end: each
 * starts where the previous one ended, so a frame that fails to write is dropped and the next
 * one covers its time, keeping the rest of the video on the recording's timeline.
 */
class JcodecVideoEncoder internal constructor(
    /** Writes one frame into the MP4. Replaced in tests to simulate a write failure. */
    private val outputFrame: (SinkImpl, VideoFrameWithPacket) -> Unit,
) : VideoEncoder {

    constructor() : this({ sink, frame -> sink.outputVideoFrame(frame) })

    private val tempFiles = TempFileHandler()
    private lateinit var tempFile: File
    private lateinit var tempChannel: FileChannelWrapper
    private lateinit var sink: SinkImpl
    private var toSinkColor: Transform? = null
    private lateinit var out: Sink

    private var held: Picture? = null
    private var framesWritten = 0L
    private var writtenUntilMs = 0L

    override fun start(out: Sink) {
        tempFile = tempFiles.createTempFile("maestro_jcodec", ".mp4")
        tempChannel = NIOUtils.writableChannel(tempFile)

        sink = SinkImpl.createWithStream(tempChannel, Format.MOV, Codec.H264, null)
        sink.init()
        toSinkColor = sink.inputColor?.let { ColorUtil.getTransform(ColorSpace.RGB, it) }

        this.out = out
    }

    override fun encodeFrame(frame: ByteArray, atMs: Long) {
        val picture = decodeEvenSized(frame)
        try {
            writeHeld(untilMs = atMs)
        } finally {
            // Held even when the previous frame failed to write, so only that frame is lost.
            held = picture
        }
    }

    override fun finish(endMs: Long) {
        try {
            // `use` closes the output whether or not the video could be finalized.
            out.buffer().use { dst ->
                finalizeTempFile(endMs)
                // With no frames jcodec still writes a header-only file; the caller expects an
                // empty output for a recording that captured nothing, so write nothing.
                if (framesWritten > 0) tempFile.source().buffer().use { dst.writeAll(it) }
            }
        } finally {
            tempFiles.close()
        }
    }

    /** Writes the last frame and the MP4 index. The temp file's channel is closed either way. */
    private fun finalizeTempFile(endMs: Long) {
        try {
            writeHeld(untilMs = endMs)
            sink.finish()
        } finally {
            // sink.finish() closes the channel too; this covers a failure before it gets there.
            runCatching { tempChannel.close() }
        }
    }

    /**
     * Writes the held frame to last until [untilMs]. The first frame starts at 0:00 rather than
     * when it arrived, so the video's 0:00 is the recording's start, not the first page change.
     */
    private fun writeHeld(untilMs: Long) {
        val picture = held ?: return
        // Cleared first: a frame that fails to write is dropped, not retried with every later one.
        held = null

        val startMs = writtenUntilMs
        val durationMs = maxOf(untilMs - startMs, 1L)
        val packet = Packet.createPacket(null, startMs, TIMESCALE, durationMs, framesWritten, FrameType.KEY, null)
        outputFrame(sink, VideoFrameWithPacket(packet, LoanerPicture(inSinkColor(picture), 0)))

        framesWritten++
        writtenUntilMs = startMs + durationMs
    }

    private fun inSinkColor(picture: Picture): Picture =
        toSinkColor?.let { transform ->
            Picture.create(picture.width, picture.height, sink.inputColor).also { transform.transform(picture, it) }
        } ?: picture

    private companion object {
        /** Milliseconds, so packet times need no conversion. */
        const val TIMESCALE = 1000

        /**
         * H264's 4:2:0 chroma needs even dimensions; Chrome scales the screencast to fit its
         * bounds and can hand back an odd edge. Trim a pixel rather than fail the recording.
         */
        fun decodeEvenSized(jpeg: ByteArray): Picture {
            val image = ByteArrayInputStream(jpeg).use { ImageIO.read(it) }
            val evenImage = image.getSubimage(0, 0, image.width and 1.inv(), image.height and 1.inv())
            return AWTUtil.fromBufferedImageRGB(evenImage)
        }
    }
}
