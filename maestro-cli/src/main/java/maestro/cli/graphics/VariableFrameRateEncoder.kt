package maestro.cli.graphics

import org.jcodec.api.transcode.PixelStore.LoanerPicture
import org.jcodec.api.transcode.SinkImpl
import org.jcodec.api.transcode.VideoFrameWithPacket
import org.jcodec.common.Codec
import org.jcodec.common.Format
import org.jcodec.common.io.SeekableByteChannel
import org.jcodec.common.model.ColorSpace
import org.jcodec.common.model.Packet
import org.jcodec.common.model.Packet.FrameType
import org.jcodec.common.model.Picture
import org.jcodec.scale.ColorUtil

/**
 * H264-in-MP4 via jcodec, where each frame carries its own duration rather than the video having
 * a fixed frame rate, so a frame that stays on screen is encoded once however long it stays.
 * maestro-web's JcodecVideoEncoder writes its frames the same way.
 */
internal class VariableFrameRateEncoder(out: SeekableByteChannel) {

    private val sink = SinkImpl.createWithStream(out, Format.MOV, Codec.H264, null).apply { init() }
    private val toSinkColor = sink.inputColor?.let { ColorUtil.getTransform(ColorSpace.RGB, it) }
    private var framesWritten = 0L

    /** Writes [picture], in RGB, to show from [startMs] for [durationMs]. */
    fun encode(picture: Picture, startMs: Long, durationMs: Long) {
        require(durationMs > 0) { "A frame must last at least 1ms, was $durationMs" }
        val packet = Packet.createPacket(null, startMs, TIMESCALE, durationMs, framesWritten, FrameType.KEY, null)
        sink.outputVideoFrame(VideoFrameWithPacket(packet, LoanerPicture(inSinkColor(picture), 0)))
        framesWritten++
    }

    /** Writes the MP4 index. Without it, the file does not play. */
    fun finish() {
        sink.finish()
    }

    private fun inSinkColor(picture: Picture): Picture =
        toSinkColor?.let { transform ->
            Picture.create(picture.width, picture.height, sink.inputColor).also { transform.transform(picture, it) }
        } ?: picture

    private companion object {
        /** Milliseconds, so packet times need no conversion. */
        const val TIMESCALE = 1000
    }
}
