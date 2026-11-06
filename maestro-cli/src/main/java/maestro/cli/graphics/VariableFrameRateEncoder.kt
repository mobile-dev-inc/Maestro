package maestro.cli.graphics

import org.jcodec.codecs.h264.H264Encoder
import org.jcodec.common.Codec
import org.jcodec.common.MuxerTrack
import org.jcodec.common.VideoCodecMeta
import org.jcodec.common.io.SeekableByteChannel
import org.jcodec.common.model.ColorSpace
import org.jcodec.common.model.Packet
import org.jcodec.common.model.Packet.FrameType
import org.jcodec.common.model.Picture
import org.jcodec.common.model.Size
import org.jcodec.containers.mp4.muxer.MP4Muxer
import org.jcodec.scale.ColorUtil
import java.nio.ByteBuffer

/**
 * H264-in-MP4 via jcodec, where each frame carries its own duration rather than the video having
 * a fixed frame rate, so a frame that stays on screen is encoded once however long it stays.
 *
 * Keyframes are placed by time, as `ffmpeg -force_key_frames` does: a frame is a keyframe when
 * [keyFrameIntervalMs] or more has passed since the last one. jcodec counts its keyframe interval
 * in frames, which at a variable frame rate could leave anything from a fraction of a second to
 * minutes between keyframes, and seeking has to decode from the last one.
 *
 * This is what jcodec's SinkImpl does with H264Encoder and MP4Muxer, but with the encoder in hand
 * to choose which frames are keyframes.
 */
internal class VariableFrameRateEncoder(
    out: SeekableByteChannel,
    private val keyFrameIntervalMs: Long = 2_000,
) {

    private val muxer = MP4Muxer.createMP4MuxerToChannel(out)
    private val encoder = H264Encoder.createH264Encoder()
    private val toYuv = ColorUtil.getTransform(ColorSpace.RGB, ColorSpace.YUV420J)
    private var track: MuxerTrack? = null
    private var buffer: ByteBuffer? = null
    private var lastKeyFrameMs: Long? = null
    private var framesWritten = 0L

    /** Writes [picture], in RGB, to show from [startMs] for [durationMs]. */
    fun encode(picture: Picture, startMs: Long, durationMs: Long) {
        require(durationMs > 0) { "A frame must last at least 1ms, was $durationMs" }
        val yuv = Picture.create(picture.width, picture.height, ColorSpace.YUV420J).also { toYuv.transform(picture, it) }

        // encodeFrame starts a keyframe once its count of frames since the last reaches
        // keyInterval, so 1 forces one and MAX_VALUE holds it off. (encodeIDRFrame would leave
        // the count at 0, making the next frame a keyframe too, and encodePFrame marks its frames
        // as keyframes.)
        val keyFrameDue = lastKeyFrameMs.let { it == null || startMs - it >= keyFrameIntervalMs }
        encoder.keyInterval = if (keyFrameDue) 1 else Int.MAX_VALUE
        val encoded = encoder.encodeFrame(yuv, bufferFor(yuv))
        if (encoded.isKeyFrame) lastKeyFrameMs = startMs

        // Not copied first, though the buffer is reused: the muxer copies what it keeps, the frame
        // into MP4's length-prefixed form and the parameter sets via jcodec's NIOUtils.duplicate.
        val packet = Packet.createPacket(
            encoded.data, startMs, TIMESCALE, durationMs, framesWritten,
            if (encoded.isKeyFrame) FrameType.KEY else FrameType.INTER, null,
        )
        trackFor(yuv).addFrame(packet)
        framesWritten++
    }

    /** Writes the MP4 index. Without it, the file does not play. */
    fun finish() {
        muxer.finish()
    }

    private fun trackFor(picture: Picture): MuxerTrack =
        track ?: muxer.addVideoTrack(
            Codec.H264,
            VideoCodecMeta.createSimpleVideoCodecMeta(Size(picture.width, picture.height), picture.color),
        ).also { track = it }

    private fun bufferFor(picture: Picture): ByteBuffer {
        val size = encoder.estimateBufferSize(picture)
        val reusable = buffer?.takeIf { it.capacity() >= size }
        return (reusable ?: ByteBuffer.allocate(size).also { buffer = it }).apply { clear() }
    }

    private companion object {
        /** Milliseconds, so packet times need no conversion. */
        const val TIMESCALE = 1000
    }
}
