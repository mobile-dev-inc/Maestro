package maestro.web.record

import okio.Sink

interface VideoEncoder : AutoCloseable {

    fun start(out: Sink)

    fun encodeFrame(frame: ByteArray)

}