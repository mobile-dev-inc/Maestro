package maestro.cli.graphics

import org.jcodec.api.FrameGrab
import org.jcodec.api.awt.AWTSequenceEncoder
import org.jcodec.common.io.NIOUtils
import java.awt.Graphics2D
import java.io.File

fun Graphics2D.use(block: (g: Graphics2D) -> Unit) {
    try {
        block(this)
    } finally {
        dispose()
    }
}

fun AWTSequenceEncoder.use(block: (encoder: AWTSequenceEncoder) -> Unit) {
    try {
        block(this)
    } finally {
        finish()
    }
}

fun useFrameGrab(file: File, block: (grab: FrameGrab) -> Unit) {
    NIOUtils.readableChannel(file).use { channelIn ->
        val grab = FrameGrab.createFrameGrab(channelIn)
        block(grab)
    }
}
