package maestro.cli.graphics

import org.jcodec.api.transcode.PixelStore
import org.jcodec.api.transcode.PixelStoreImpl
import org.jcodec.api.transcode.SinkImpl
import org.jcodec.codecs.h264.H264Encoder
import org.jcodec.common.Codec
import org.jcodec.common.Format
import org.jcodec.common.VideoCodecMeta
import org.jcodec.common.io.NIOUtils
import org.jcodec.common.io.SeekableByteChannel
import org.jcodec.common.model.ColorSpace
import org.jcodec.common.model.Packet
import org.jcodec.common.model.Picture
import org.jcodec.common.model.Rational
import org.jcodec.common.model.Size
import org.jcodec.scale.ColorUtil
import org.jcodec.scale.Transform
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.io.Closeable
import java.nio.ByteBuffer

/**
 * JCodec 0.2.5 helper that encodes each unique RGB picture once and stretches
 * that sample's duration across identical output frames (timescale = fps.num,
 * one-frame duration = fps.den, pts starting at 0).
 */
internal class JcodecHoldFrameEncoder(
    out: SeekableByteChannel,
    fps: Rational,
    private val width: Int,
    private val height: Int,
) : Closeable {

    private val sink: SinkImpl = SinkImpl.createWithStream(out, Format.MOV, Codec.H264, null)
    private val encoder: H264Encoder = H264Encoder.createH264Encoder()
    private val rgb: Picture = Picture.create(width, height, ColorSpace.RGB)
    private val pixelStore: PixelStore = PixelStoreImpl()
    private val transform: Transform
    private val timescale: Int = fps.num
    private val frameDuration: Long = fps.den.toLong()

    private var encodeBuf: ByteBuffer? = null
    private var codecMeta: VideoCodecMeta? = null

    private var pendingData: ByteBuffer? = null
    private var pendingKey = true
    private var pendingPts = 0L
    private var pendingFrameNo = 0L
    private var pendingDurationTicks = 0L
    private var nextPts = 0L
    private var nextFrameNo = 0L

    init {
        sink.init()
        val inputColor = checkNotNull(sink.inputColor) { "H.264 sink has no input color" }
        transform = ColorUtil.getTransform(ColorSpace.RGB, inputColor)
    }

    fun encodeImage(image: BufferedImage) {
        encodeBgra((image.raster.dataBuffer as DataBufferByte).data)
    }

    fun holdPrevious() {
        check(pendingData != null) { "holdPrevious without an encoded frame" }
        pendingDurationTicks += frameDuration
    }

    fun finish() {
        flushPending()
        sink.finish()
    }

    override fun close() {
        finish()
    }

    private fun encodeBgra(bgra: ByteArray) {
        flushPending()
        copyBgraToRgb(bgra, rgb)
        val inputColor = checkNotNull(sink.inputColor) { "H.264 sink has no input color" }
        val loaned = pixelStore.getPicture(rgb.width, rgb.height, inputColor)
        try {
            transform.transform(rgb, loaned.picture)
            val yuv = loaned.picture
            if (codecMeta == null) {
                codecMeta = VideoCodecMeta.createSimpleVideoCodecMeta(
                    Size(yuv.width, yuv.height),
                    yuv.color,
                )
            }
            val needed = encoder.estimateBufferSize(yuv)
            var buf = encodeBuf
            if (buf == null || buf.capacity() < needed) {
                buf = ByteBuffer.allocate(needed)
                encodeBuf = buf
            }
            buf.clear()
            val encoded = encoder.encodeFrame(yuv, buf)
            pendingData = NIOUtils.clone(encoded.data)
            pendingKey = encoded.isKeyFrame
            pendingPts = nextPts
            pendingFrameNo = nextFrameNo
            pendingDurationTicks = frameDuration
        } finally {
            pixelStore.putBack(loaned)
        }
    }

    private fun flushPending() {
        val data = pendingData ?: return
        if (pendingDurationTicks <= 0L) return
        val packet = Packet.createPacket(
            data,
            pendingPts,
            timescale,
            pendingDurationTicks,
            pendingFrameNo,
            if (pendingKey) Packet.FrameType.KEY else Packet.FrameType.INTER,
            null,
        )
        sink.outputVideoPacket(packet, checkNotNull(codecMeta))
        nextPts = pendingPts + pendingDurationTicks
        nextFrameNo += pendingDurationTicks / frameDuration
        pendingDurationTicks = 0L
        pendingData = null
    }

    private fun copyBgraToRgb(bgra: ByteArray, rgbPicture: Picture) {
        val dst = rgbPicture.getPlaneData(0)
        var srcIndex = 0
        var dstIndex = 0
        val pixelCount = width * height
        var pixel = 0
        while (pixel < pixelCount) {
            val b = bgra[srcIndex].toInt() and 0xff
            val g = bgra[srcIndex + 1].toInt() and 0xff
            val r = bgra[srcIndex + 2].toInt() and 0xff
            srcIndex += 4
            dst[dstIndex++] = (r - 128).toByte()
            dst[dstIndex++] = (g - 128).toByte()
            dst[dstIndex++] = (b - 128).toByte()
            pixel++
        }
    }
}
