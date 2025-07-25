package maestro.web.record

import okio.Sink
import okio.buffer
import okio.source
import org.jcodec.api.SequenceEncoder
import org.jcodec.scale.AWTUtil
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

class JcodecVideoEncoder : VideoEncoder {

    private lateinit var sequenceEncoder: SequenceEncoder
    private lateinit var tempFile: File
    private lateinit var out: Sink

    override fun start(out: Sink) {
        tempFile = File.createTempFile("maestro_jcodec", ".mp4")

        sequenceEncoder = SequenceEncoder.create2997Fps(tempFile)

        this.out = out
    }

    override fun encodeFrame(frame: ByteArray) {
        val image = ByteArrayInputStream(frame).use { ImageIO.read(it) }

        val picture = AWTUtil.fromBufferedImageRGB(image)

        sequenceEncoder.encodeNativeFrame(picture)
    }

    override fun close() {
        sequenceEncoder.finish()

        try {
            out.buffer().use {
                it.writeAll(tempFile.source().buffer())
            }
        } finally {
            tempFile.delete()
        }
    }

}